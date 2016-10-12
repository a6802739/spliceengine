/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.splicemachine.derby.impl.stats;

import com.splicemachine.EngineDriver;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.sql.compile.CostEstimate;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.sql.dictionary.PartitionStatisticsDescriptor;
import com.splicemachine.db.iapi.stats.PartitionStatistics;
import com.splicemachine.db.iapi.stats.PartitionStatisticsImpl;
import com.splicemachine.db.iapi.stats.TableStatistics;
import com.splicemachine.db.iapi.stats.TableStatisticsImpl;
import com.splicemachine.db.iapi.store.access.StoreCostController;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.storage.Partition;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;
import org.spark_project.guava.base.Function;
import org.spark_project.guava.collect.Lists;
import org.spark_project.guava.collect.Maps;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 *
 */
public class StoreCostControllerImpl implements StoreCostController {

    private static Logger LOG = Logger.getLogger(StoreCostControllerImpl.class);

    private static final Function<? super Partition,? extends String> partitionNameTransform = new Function<Partition, String>(){
        @Override public String apply(Partition hRegionInfo) {
            assert hRegionInfo != null : "regionInfo cannot be null!";
            return hRegionInfo.getName();
        }
    };

    private static final Function<PartitionStatisticsDescriptor,String> partitionStatisticsTransform = new Function<PartitionStatisticsDescriptor, String>(){
        @Override public String apply(PartitionStatisticsDescriptor desc){
            assert desc!=null: "Descriptor cannot be null!";
            return desc.getPartitionId();
        }
    };

    private final double openLatency;
    private final double closeLatency;
    private final double fallbackNullFraction;
    private final double extraQualifierMultiplier;
    private int missingPartitions;
    private TableStatistics tableStatistics;
    private final double fallbackLocalLatency;
    private final double fallbackRemoteLatencyRatio;


    public StoreCostControllerImpl(long conglomerateId, List<PartitionStatisticsDescriptor> partitionStatistics) throws StandardException {
        SConfiguration config = EngineDriver.driver().getConfiguration();
        openLatency = config.getFallbackOpencloseLatency();
        closeLatency = config.getFallbackOpencloseLatency();
        fallbackNullFraction = config.getFallbackNullFraction();
        extraQualifierMultiplier = config.getOptimizerExtraQualifierMultiplier();
        fallbackLocalLatency =EngineDriver.driver().getConfiguration().getFallbackLocalLatency();
        fallbackRemoteLatencyRatio =EngineDriver.driver().getConfiguration().getFallbackRemoteLatencyRatio();

        byte[] table = Bytes.toBytes(Long.toString(conglomerateId));
        List<Partition> partitions = new ArrayList<>();
        getPartitions(table, partitions, false);
        List<String> partitionNames = Lists.transform(partitions,partitionNameTransform);
        LanguageConnectionContext lcc = (LanguageConnectionContext) ContextService.getContext(LanguageConnectionContext.CONTEXT_ID);
        DataDictionary dd = lcc.getDataDictionary();
        Map<String,PartitionStatisticsDescriptor> partitionMap = Maps.uniqueIndex(partitionStatistics,partitionStatisticsTransform);
        if (partitions.size() < partitionStatistics.size()) {
            // reload if partition cache contains outdated data for this table
            partitions.clear();
            getPartitions(table, partitions, true);
        }
        List<PartitionStatistics> partitionStats = new ArrayList<>(partitions.size());
        String tableId = Long.toString(conglomerateId);
        PartitionStatisticsDescriptor tStats;


        for(String partitionName : partitionNames){
            tStats = partitionMap.get(partitionName);
            if(tStats==null) {
                missingPartitions++;
                continue; //skip missing partitions entirely
            }
            partitionStats.add(new PartitionStatisticsImpl(tStats));
        }

        /*
         * We cannot have *zero* completely populated items unless we have no column statistics, but in that case
         * we have no table information either, so just return an empty list and let the caller figure out
         * what to do
         */
        tableStatistics = (partitionStats.size() <= 0)?
            RegionLoadStatistics.getTableStatistics(tableId,partitions)
            :new TableStatisticsImpl(tableId,partitionStats);

    }

    @Override
    public void close() throws StandardException {

    }

    @Override
    public void getFetchFromFullKeyCost(BitSet validColumns, int access_type, CostEstimate cost) throws StandardException {
                /*
         * This is the cost to read a single row from a PK or indexed table (without any associated index lookups).
         * Since we know the full key, we have two scenarios:
         *
         * 1. The table is unique (i.e. a primary key or unique index lookup)
         * 2. The table is not unique (i.e. non-unique index, grouped tables, etc.)
         *
         * In the first case, the cost is just remoteReadLatency(), since we know we will only be scanning
         * a single row. In the second case, however, it's the cost of scanning however many rows match the query.
         *
         * The first scenario uses this method, but the second scenario actually uses getScanCost(), so we're safe
         * assuming just a single row here
         */
        double columnSizeFactor = tableStatistics.columnSizeFactor(validColumns);

        cost.setRemoteCost(getRemoteLatency()*columnSizeFactor*tableStatistics.avgRowWidth());
        cost.setLocalCost(fallbackLocalLatency);
        cost.setEstimatedHeapSize((long) columnSizeFactor*tableStatistics.avgRowWidth());
        cost.setNumPartitions(1);
        cost.setEstimatedRowCount(1l);
        cost.setOpenCost(openLatency);
        cost.setCloseCost(closeLatency);
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"getFetchFromFullKeyCost={columnSizeFactor=%f, cost=%s" +
                    "cost=%s",columnSizeFactor,cost);


    }

    @Override
    public RowLocation newRowLocationTemplate() throws StandardException {
        return null;
    }

    @Override
    public double getSelectivity(int columnNumber, DataValueDescriptor start, boolean includeStart, DataValueDescriptor stop, boolean includeStop) {
        return tableStatistics.rangeSelectivity(start,stop,includeStart,includeStop,columnNumber);
    }

    @Override
    public double rowCount() {
        if (missingPartitions > 0)
            return tableStatistics.rowCount() + tableStatistics.rowCount()*(missingPartitions/tableStatistics.numPartitions());
        else
            return tableStatistics.rowCount();
    }

    @Override
    public double nonNullCount(int columnNumber) {
        if (missingPartitions > 0)
            return tableStatistics.notNullCount(columnNumber-1) + tableStatistics.notNullCount(columnNumber-1)*(missingPartitions/tableStatistics.numPartitions());
        else
            return tableStatistics.notNullCount(columnNumber-1);
    }

    @Override
    public double nullSelectivity(int columnNumber) {
            return (tableStatistics.rowCount() - tableStatistics.notNullCount(columnNumber-1))/tableStatistics.rowCount();
    }

    @Override
    public long cardinality(int columnNumber) {
        if (missingPartitions > 0)
            return tableStatistics.notNullCount(columnNumber-1) + tableStatistics.notNullCount(columnNumber-1)*(missingPartitions/tableStatistics.numPartitions());
        else
            return tableStatistics.notNullCount(columnNumber-1);
    }

    @Override
    public long getConglomerateAvgRowWidth() {
        return tableStatistics.avgRowWidth();
    }

    @Override
    public long getBaseTableAvgRowWidth() {
        return tableStatistics.avgRowWidth();
    }

    @Override
    public double getLocalLatency() {
        return fallbackLocalLatency;
    }

    @Override
    public double getRemoteLatency() {
        return fallbackLocalLatency*fallbackRemoteLatencyRatio;
    }

    @Override
    public double getOpenLatency() {
        return openLatency;
    }

    @Override
    public double getCloseLatency() {
        return closeLatency;
    }

    @Override
    public int getNumPartitions() {
        return missingPartitions+tableStatistics.numPartitions();
    }

    @Override
    public double conglomerateColumnSizeFactor(BitSet validColumns) {
        return 1.0;
    }

    @Override
    public double baseTableColumnSizeFactor(BitSet validColumns) {
        return 1.0;
    }

    @Override
    public double baseRowCount() {
        return rowCount();
    }

    @Override
    public DataValueDescriptor minValue(int columnNumber) {
        return tableStatistics.minValue(columnNumber-1);
    }

    @Override
    public DataValueDescriptor maxValue(int columnNumber) {
        return tableStatistics.maxValue(columnNumber-1);
    }

    @Override
    public long getEstimatedRowCount() throws StandardException {
        return (long)rowCount();
    }

    public static Txn getTxn(TxnView wrapperTxn) throws ExecutionException {
        try {
            return SIDriver.driver().lifecycleManager().beginChildTransaction(wrapperTxn, Txn.IsolationLevel.READ_UNCOMMITTED,null);
        } catch (IOException e) {
            throw new ExecutionException(e);
        }
    }

    public static int getPartitions(byte[] table, List<Partition> partitions, boolean refresh) throws StandardException {
        try {
            partitions.addAll(SIDriver.driver().getTableFactory().getTable(table).subPartitions(refresh));
            return partitions.size();
        } catch (Exception ioe) {
            throw StandardException.plainWrapException(ioe);
        }
    }

    public static int getPartitions(String table, List<Partition> partitions) throws StandardException {
        return getPartitions(table,partitions,false);
    }

    public static int getPartitions(String table, List<Partition> partitions, boolean refresh) throws StandardException {
        try {
            partitions.addAll(SIDriver.driver().getTableFactory().getTable(table).subPartitions(refresh));
            return partitions.size();
        } catch (Exception ioe) {
            throw StandardException.plainWrapException(ioe);
        }
    }
}