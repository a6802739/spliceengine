package com.splicemachine.derby.stream.spark;

import com.clearspring.analytics.util.Lists;
import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.api.PartitionAdmin;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.stats.ColumnStatisticsImpl;
import com.splicemachine.db.iapi.stats.ColumnStatisticsMerge;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.iapi.types.SQLBlob;
import com.splicemachine.db.iapi.types.SQLLongint;
import com.splicemachine.db.iapi.types.SQLVarchar;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.ddl.DDLMessage;
import com.splicemachine.derby.impl.SpliceSpark;
import com.splicemachine.derby.impl.load.ImportUtils;
import com.splicemachine.derby.impl.sql.execute.operations.InsertOperation;
import com.splicemachine.derby.impl.sql.execute.operations.LocatedRow;
import com.splicemachine.derby.impl.sql.execute.sequence.SpliceSequence;
import com.splicemachine.derby.impl.store.access.SpliceTransactionManager;
import com.splicemachine.derby.impl.store.access.base.SpliceConglomerate;
import com.splicemachine.derby.stream.function.HFileGenerator;
import com.splicemachine.derby.stream.function.RowAndIndexGenerator;
import com.splicemachine.derby.stream.function.RowKeyStatisticsFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.derby.stream.output.HBaseBulkImporter;
import com.splicemachine.pipeline.ErrorState;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.protobuf.ProtoUtil;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.storage.Partition;
import com.splicemachine.utils.SpliceLogUtils;
import com.yahoo.sketches.quantiles.ItemsSketch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.log4j.Logger;
import scala.Tuple2;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.util.*;

/**
 * Created by jyuan on 3/14/17.
 */
public class SparkHBaseBulkImport implements HBaseBulkImporter {

    private static final Logger LOG=Logger.getLogger(SparkHBaseBulkImport.class);

    private DataSet dataSet;
    private String tableVersion;
    private int[] pkCols;
    private RowLocation[] autoIncrementRowLocationArray;
    private long heapConglom;
    private ExecRow execRow;
    private SpliceSequence[] spliceSequences;
    private OperationContext operationContext;
    private TxnView txn;
    private String bulkImportDirectory;
    private boolean samplingOnly;

    public SparkHBaseBulkImport(){
    }

    public SparkHBaseBulkImport(DataSet dataSet,
                                String tableVersion,
                                int[] pkCols,
                                RowLocation[] autoIncrementRowLocationArray,
                                long heapConglom,
                                ExecRow execRow,
                                SpliceSequence[] spliceSequences,
                                OperationContext operationContext,
                                TxnView txn,
                                String bulkImportDirectory,
                                boolean samplingOnly) {
        this.dataSet = dataSet;
        this.tableVersion = tableVersion;
        this.autoIncrementRowLocationArray = autoIncrementRowLocationArray;
        this.pkCols = pkCols;
        this.heapConglom = heapConglom;
        this.execRow = execRow;
        this.spliceSequences = spliceSequences;
        this.operationContext = operationContext;
        this.txn = txn;
        this.bulkImportDirectory = bulkImportDirectory;
        this.samplingOnly = samplingOnly;
    }

    /**
     *  1) Sample data to calculate key/value size and key histogram
     *  2) Calculate cut points and split table and indexes
     *  3) Read and encode data for table and indexes, hash to the partition where its rowkey falls into
     *  4) Sort keys in each partition and write to HFiles
     *  5) Load HFiles to HBase
     * @return
     * @throws StandardException
     */
    public DataSet<LocatedRow> write() throws StandardException {

        // collect index information for the table
        Activation activation = operationContext.getActivation();
        DataDictionary dd = activation.getLanguageConnectionContext().getDataDictionary();
        ConglomerateDescriptor cd = dd.getConglomerateDescriptor(heapConglom);
        TableDescriptor td = dd.getTableDescriptor(cd.getTableID());
        ConglomerateDescriptorList list = td.getConglomerateDescriptorList();
        List<Long> allCongloms = Lists.newArrayList();
        allCongloms.add(td.getHeapConglomerateId());
        ArrayList<DDLMessage.TentativeIndex> tentativeIndexList = new ArrayList();

        for (ConglomerateDescriptor searchCD :list) {
            if (searchCD.isIndex() && !searchCD.isPrimaryKey()) {
                DDLMessage.DDLChange ddlChange = ProtoUtil.createTentativeIndexChange(txn.getTxnId(),
                        activation.getLanguageConnectionContext(),
                        td.getHeapConglomerateId(), searchCD.getConglomerateNumber(),
                        td, searchCD.getIndexDescriptor());
                tentativeIndexList.add(ddlChange.getTentativeIndex());
                allCongloms.add(searchCD.getConglomerateNumber());
            }
        }

        // TODO: an option to skip sampling and statistics collection
        // Sample the data set and collect statistics for key distributions
        SConfiguration sConfiguration = HConfiguration.getConfiguration();
        double sampleFraction = sConfiguration.getBulkImportSampleFraction();
        DataSet sampledDataSet = dataSet.sampleWithoutReplacement(sampleFraction);

        // encode key/vale pairs for table and indexes
        RowAndIndexGenerator rowAndIndexGenerator =
                new RowAndIndexGenerator(pkCols,tableVersion,execRow,autoIncrementRowLocationArray,
                        spliceSequences,heapConglom, txn,operationContext,tentativeIndexList);
        DataSet sampleRowAndIndexes = sampledDataSet.flatMap(rowAndIndexGenerator);

        // collect statistics for encoded key/value, include size and histgram
        RowKeyStatisticsFunction statisticsFunction =
                new RowKeyStatisticsFunction(td.getHeapConglomerateId(), tentativeIndexList);
        DataSet keyStatistics = sampleRowAndIndexes.mapPartitions(statisticsFunction);

        List<Tuple2<Long, Tuple2<Long, ColumnStatisticsImpl>>> result = keyStatistics.collect();

        // Calculate cut points for main table and index tables
        List<Tuple2<Long, byte[][]>> cutPoints = getCutPoints(result);

        // dump cut points to file system for reference
        dumpCutPoints(cutPoints);

        if (!samplingOnly) {

            // split table and indexes using the calculated cutpoints
            splitTables(cutPoints);


            // get the actual start/end key for each partition after split
            List<Tuple2<Long, List<HFileGenerator.HashBucketKey>>> hashBucketKeys =
                    getHashBucketKeys(allCongloms, bulkImportDirectory);

            // Read data again and encode rows in main table and index
            rowAndIndexGenerator = new RowAndIndexGenerator(pkCols, tableVersion, execRow,
                    autoIncrementRowLocationArray, spliceSequences, heapConglom, txn,
                    operationContext, tentativeIndexList);
            DataSet rowAndIndexes = dataSet.flatMap(rowAndIndexGenerator);

            // Generate HFiles
            HFileGenerator writer = new HFileGenerator(operationContext, txn.getTxnId(),
                    heapConglom, bulkImportDirectory, hashBucketKeys);
            DataSet HFileSet = rowAndIndexes.mapPartitions(writer);
            List<String> files = HFileSet.collect();

            if (LOG.isDebugEnabled()) {
                SpliceLogUtils.debug(LOG, "created %d HFiles", files.size());
            }

            bulkLoad(hashBucketKeys);
        }

        ValueRow valueRow=new ValueRow(3);
        valueRow.setColumn(1,new SQLLongint(operationContext.getRecordsWritten()));
        valueRow.setColumn(2,new SQLLongint());
        valueRow.setColumn(3,new SQLVarchar());
        InsertOperation insertOperation=((InsertOperation)operationContext.getOperation());
        if(insertOperation!=null && operationContext.isPermissive()) {
            long numBadRecords = operationContext.getBadRecords();
            valueRow.setColumn(2,new SQLLongint(numBadRecords));
            if (numBadRecords > 0) {
                String fileName = operationContext.getStatusDirectory();
                valueRow.setColumn(3,new SQLVarchar(fileName));
                if (insertOperation.isAboveFailThreshold(numBadRecords)) {
                    throw ErrorState.LANG_IMPORT_TOO_MANY_BAD_RECORDS.newException(fileName);
                }
            }
        }
        return new SparkDataSet<>(SpliceSpark.getContext().parallelize(Collections.singletonList(new LocatedRow(valueRow)), 1));
    }

    /**
     * Output cut points to files
     * @param cutPointsList
     * @throws IOException
     */
    private void dumpCutPoints(List<Tuple2<Long, byte[][]>> cutPointsList) throws StandardException {

        BufferedWriter br = null;
        try {
            Configuration conf = HConfiguration.unwrapDelegate();
            FileSystem fs = FileSystem.get(URI.create(bulkImportDirectory), conf);

            for (Tuple2<Long, byte[][]> t : cutPointsList) {
                Long conglomId = t._1;

                Path path = new Path(bulkImportDirectory, conglomId.toString());
                FSDataOutputStream os = fs.create(new Path(path, "cutpoints"));
                br = new BufferedWriter( new OutputStreamWriter( os, "UTF-8" ) );

                byte[][] cutPoints = t._2;

                for (byte[] cutPoint : cutPoints) {
                    br.write(Bytes.toStringBinary(cutPoint) + "\n");
                }
                br.close();
            }
        }catch (IOException e) {
            throw StandardException.plainWrapException(e);
        } finally {
            try {
                if (br != null)
                    br.close();
            } catch (IOException e) {
                throw StandardException.plainWrapException(e);
            }
        }
    }
    /**
     * Bulk load HFiles to HBase
     * @param hashBucketKeys
     * @throws StandardException
     */
    private void bulkLoad(List<Tuple2<Long, List<HFileGenerator.HashBucketKey>>> hashBucketKeys) throws StandardException{
        try {
            Configuration conf = HConfiguration.unwrapDelegate();
            LoadIncrementalHFiles loader = new LoadIncrementalHFiles(conf);

            for (Tuple2<Long, List<HFileGenerator.HashBucketKey>> t : hashBucketKeys) {
                Long conglom = t._1;
                List<HFileGenerator.HashBucketKey> l = t._2;
                HTable table = new HTable(conf, TableName.valueOf("splice:" + conglom));
                for (HFileGenerator.HashBucketKey hashBucketKey : l) {
                    Path path = new Path(hashBucketKey.getPath()).getParent();
                    loader.doBulkLoad(path, table);
                    if (LOG.isDebugEnabled()) {
                        SpliceLogUtils.debug(LOG, "Loaded file %s", path.toString());
                    }
                }
            }
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    /**
     * Generate a file name
     * @param dir
     * @return
     * @throws IOException
     */

    public static Path getRandomFilename(final Path dir)
            throws IOException{
        return new Path(dir, java.util.UUID.randomUUID().toString().replaceAll("-",""));
    }

    /**
     * Get actual partition boundaries for each table and index
     * @param congloms
     * @return
     */
    private List<Tuple2<Long, List<HFileGenerator.HashBucketKey>>> getHashBucketKeys(
            List<Long> congloms,
            String bulkImportDirectory) throws StandardException {

        try {
            // Create bulk import dorectory if it does not exist
            Path bulkImportPath = new Path(bulkImportDirectory);

            List<Tuple2<Long, List<HFileGenerator.HashBucketKey>>> tablePartitionBoundaries = Lists.newArrayList();
            for (Long conglom : congloms) {
                Path tablePath = new Path(bulkImportPath, conglom.toString());
                SIDriver driver = SIDriver.driver();
                try (PartitionAdmin pa = driver.getTableFactory().getAdmin()) {
                    Iterable<? extends Partition> partitions = pa.allPartitions(conglom.toString());
                    List<HFileGenerator.HashBucketKey> b = Lists.newArrayList();
                    if (LOG.isDebugEnabled()) {
                        SpliceLogUtils.debug(LOG, "partition information for table %d", conglom);
                    }
                    int count = 0;
                    for (Partition partition : partitions) {
                        byte[] startKey = partition.getStartKey();
                        byte[] endKey = partition.getEndKey();
                        Path regionPath = getRandomFilename(tablePath);
                        Path familyPath = new Path(regionPath, "V");
                        b.add(new HFileGenerator.HashBucketKey(conglom, startKey, endKey, familyPath.toString()));
                        count++;
                        if (LOG.isDebugEnabled()) {
                            SpliceLogUtils.debug(LOG, "start key: %s", Bytes.toHex(startKey));
                            SpliceLogUtils.debug(LOG, "end key: %s", Bytes.toHex(endKey));
                            SpliceLogUtils.debug(LOG, "path = %s");
                        }
                    }
                    if (LOG.isDebugEnabled()) {
                        SpliceLogUtils.debug(LOG, "number of partition: %d", count);
                    }
                    tablePartitionBoundaries.add(new Tuple2<>(conglom, b));
                }
            }
            return tablePartitionBoundaries;
        } catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }
    /**
     * Calculate cut points according to statistics. Number of cut points is decided by max region size.
     * @param statistics
     * @return
     * @throws StandardException
     */
    private List<Tuple2<Long, byte[][]>> getCutPoints(
            List<Tuple2<Long, Tuple2<Long, ColumnStatisticsImpl>>> statistics) throws StandardException{
        Map<Long, Tuple2<Long, ColumnStatisticsImpl>> mergedStatistics = mergeResults(statistics);
        List<Tuple2<Long, byte[][]>> result = Lists.newArrayList();

        SConfiguration sConfiguration = HConfiguration.getConfiguration();
        long maxRegionSize = sConfiguration.getRegionMaxFileSize();

        // determine how many regions the table/index should be split into
        Map<Long, Integer> numPartitions = new HashMap<>();
        for (Long conglomId : mergedStatistics.keySet()) {
            Tuple2<Long, ColumnStatisticsImpl> stats = mergedStatistics.get(conglomId);
            long size = stats._1;
            int numPartition = (int)(size/(maxRegionSize)) + 1;
            if (numPartition > 1) {
                numPartitions.put(conglomId, numPartition);
            }
        }

        // calculate cut points for each table/index using histogram
        for (Long conglomId : numPartitions.keySet()) {
            int numPartition = numPartitions.get(conglomId);
            byte[][] cutPoints = new byte[numPartition-1][];

            ColumnStatisticsImpl columnStatistics = mergedStatistics.get(conglomId)._2;
            ItemsSketch itemsSketch = columnStatistics.getQuantilesSketch();
            for (int i = 1; i < numPartition; ++i) {
                SQLBlob blob = (SQLBlob) itemsSketch.getQuantile(i*1.0d/(double)numPartition);
                cutPoints[i-1] = blob.getBytes();
            }
            Tuple2<Long, byte[][]> tuple = new Tuple2<>(conglomId, cutPoints);
            result.add(tuple);
        }
        return result;
    }

    /**
     * Split a table using cut points
     * @param cutPointsList
     * @throws StandardException
     */
    private void splitTables(List<Tuple2<Long, byte[][]>> cutPointsList) throws StandardException {
        SIDriver driver=SIDriver.driver();
        try(PartitionAdmin pa = driver.getTableFactory().getAdmin()){
            for (Tuple2<Long, byte[][]> tuple : cutPointsList) {
                String table = tuple._1.toString();
                byte[][] cutpoints = tuple._2;
                if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.debug(LOG, "split keys for table %s", table);
                    for(byte[] cutpoint : cutpoints) {
                        SpliceLogUtils.debug(LOG, "%s", Bytes.toHex(cutpoint));
                    }
                }
                pa.splitTable(table, cutpoints);
            }
        }
        catch (IOException e) {
            e.printStackTrace();
            throw StandardException.plainWrapException(e);
        }
    }

    /**
     * Merge statistics from each RDD partition
     * @param tuples
     * @return
     * @throws StandardException
     */
    private Map<Long, Tuple2<Long, ColumnStatisticsImpl>> mergeResults(
            List<Tuple2<Long, Tuple2<Long, ColumnStatisticsImpl>>> tuples) throws StandardException{

        Map<Long, ColumnStatisticsMerge> sm = new HashMap<>();
        Map<Long, Long> sizeMap = new HashMap<>();

        for (Tuple2<Long,Tuple2<Long, ColumnStatisticsImpl>> t : tuples) {
            Long conglomId = t._1;
            Long size = t._2._1;

            // Merge statistics for keys
            ColumnStatisticsImpl cs = t._2._2;
            ColumnStatisticsMerge columnStatisticsMerge = sm.get(conglomId);
            if (columnStatisticsMerge == null) {
                columnStatisticsMerge = new ColumnStatisticsMerge();
                sm.put(conglomId, columnStatisticsMerge);
            }
            columnStatisticsMerge.accumulate(cs);

            // merge key/value size from all partition
            Long totalSize = sizeMap.get(conglomId);
            if (totalSize == null)
                totalSize = new Long(0);
            totalSize += size;
            sizeMap.put(conglomId, totalSize);
        }

        Map<Long, Tuple2<Long, ColumnStatisticsImpl>> statisticsMap = new HashMap<>();
        for (Long conglomId : sm.keySet()) {
            Long totalSize = sizeMap.get(conglomId);
            ColumnStatisticsImpl columnStatistics = sm.get(conglomId).terminate();
            statisticsMap.put(conglomId, new Tuple2(totalSize, columnStatistics));
        }

        return statisticsMap;
    }
}