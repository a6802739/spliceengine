package com.splicemachine.pipeline.writehandler;

import java.io.IOException;
import java.util.List;

import com.carrotsearch.hppc.BitSet;
import org.apache.log4j.Logger;

import com.splicemachine.constants.bytes.BytesUtil;
import com.splicemachine.derby.impl.sql.execute.index.IndexTransformer;
import com.splicemachine.hbase.KVPair;
import com.splicemachine.pipeline.api.CallBuffer;
import com.splicemachine.pipeline.api.WriteContext;
import com.splicemachine.pipeline.impl.WriteResult;
import com.splicemachine.utils.SpliceLogUtils;

/**
 * Intercepts UPDATE/UPSERT/INSERT/DELETE mutations to a base table and sends corresponding mutations to the index table.
 *
 * @author Scott Fines
 *         Created on: 5/1/13
 */
public class IndexWriteHandler extends AbstractIndexWriteHandler {

    private static final Logger LOG = Logger.getLogger(IndexWriteHandler.class);

    private final IndexTransformer transformer;
    private CallBuffer<KVPair> indexBuffer;
    private final int expectedWrites;

    public IndexWriteHandler(BitSet indexedColumns,
                             byte[] indexConglomBytes,
                             BitSet descColumns,
                             boolean keepState,
                             int expectedWrites,
                             IndexTransformer transformer){
        super(indexedColumns, indexConglomBytes, descColumns, keepState);
        this.expectedWrites = expectedWrites;
        this.transformer = transformer;
    }

    @Override
    public void next(List<KVPair> mutations, WriteContext ctx) {
        throw new RuntimeException("Not Supported");
    }

    @Override
    protected void subFlush(WriteContext ctx) throws Exception {
        if (indexBuffer != null && ! ctx.skipIndexWrites()) {
            indexBuffer.flushBuffer();
            // indexBuffer.close(); // Do not block
        }
    }

    @Override
    public void flush(WriteContext ctx) throws IOException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "flush");
        super.flush(ctx);
    }

    @Override
    public void close(WriteContext ctx) throws IOException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "close");
        super.close(ctx);
    }

    @Override
    public void subClose(WriteContext ctx) throws Exception {
        if (indexBuffer != null) {
            indexBuffer.close(); // Blocks
        }
    }

    @Override
    protected boolean isHandledMutationType(KVPair.Type type) {
        return type == KVPair.Type.DELETE || type == KVPair.Type.CANCEL ||
            type == KVPair.Type.UPDATE || type == KVPair.Type.INSERT ||
            type == KVPair.Type.UPSERT;
    }

    @Override
    public boolean updateIndex(KVPair mutation, WriteContext ctx) {
        if (ctx.skipIndexWrites()) {
            return true;
        }
        if (!ensureBufferReader(mutation, ctx))
            return false;

        switch(mutation.getType()) {
            case INSERT:
                return createIndexRecord(mutation, ctx);
            case UPDATE:
                if (transformer.areIndexKeysModified(mutation, indexedColumns)) { // Do I need to update?
                    deleteIndexRecord(mutation, ctx);
                    KVPair kvPair=indexBuffer.lastElement();
                    return createIndexRecord(mutation, ctx,kvPair);
                }
                return true; // No index columns modifies ignore...
            case UPSERT:
                deleteIndexRecord(mutation, ctx);
                KVPair kvPair=indexBuffer.lastElement();
                return createIndexRecord(mutation, ctx,kvPair);
            case DELETE:
                return deleteIndexRecord(mutation, ctx);
            case CANCEL:
                if (transformer.isUniqueIndex())
                    return true;
                throw new RuntimeException("Not Valid Execution Path");
            case EMPTY_COLUMN:
            case FOREIGN_KEY_PARENT_EXISTENCE_CHECK:
            case FOREIGN_KEY_CHILDREN_EXISTENCE_CHECK:
            default:
                throw new RuntimeException("Not Valid Execution Path");
        }
    }

    private boolean createIndexRecord(KVPair mutation, WriteContext ctx) {
        return createIndexRecord(mutation, ctx,null);
    }

    private boolean createIndexRecord(KVPair mutation, WriteContext ctx,KVPair deleteMutation) {
        try {
            boolean add=true;
            KVPair newIndex = transformer.translate(mutation);
            newIndex.setType(KVPair.Type.INSERT);
            if(deleteMutation!=null && newIndex.rowKeySlice().equals(deleteMutation.rowKeySlice())){
                /*
                 * DB-4165: When we do an update to the base table, that translates to a delete
                 * and then an insert in the index. For situations where we update the indexed fields
                 * to different values, this is fine because the delete will go to one HBase row, and the
                 * insert to another. However, if you update an indexed field by setting it to the same value
                 * (i.e. update foo set bar = bar), then the insert and the delete will end up going to the same
                 * location, and the result is an insert and a delete on the same row with the same transaction.
                 * The SI module treats this as a delete (because there is no anti-tombstone record at that location),
                 * and thus the row goes missing from the index; the end result is a corrupted index.
                 *
                 * To avoid this scenario, we check for whether the insert and the delete have the same row key. If
                 * they do, then we hijack the previous KVPair(the deleteMutation), and change it into an update mutation
                 * instead. That way, we still get the WWConflict detection, but we don't have an insert and a delete
                 * competing for the row results.
                 */
                deleteMutation.setValue(newIndex.getValue());
                deleteMutation.setType(KVPair.Type.UPDATE);
                newIndex = deleteMutation;
                add=false;
            }
            if(keepState) {
                this.indexToMainMutationMap.put(newIndex, mutation);
            }
            if(add)
                indexBuffer.add(newIndex);
        } catch (Exception e) {
            ctx.failed(mutation, WriteResult.failed(e.getClass().getSimpleName() + ":" + e.getMessage()));
            return false;
        }
        return true;
    }

    private boolean deleteIndexRecord(KVPair mutation, WriteContext ctx) {
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "index delete with %s", mutation);

    	/*
         * To delete the correct index row, we do the following:
         *
         * 1. do a Get() on all the indexed columns of the main table
         * 2. transform the results into an index row (as if we were inserting it)
         * 3. issue a delete against the index table
         */
        try {
            KVPair indexDelete = transformer.createIndexDelete(mutation, ctx, indexedColumns);
            if (indexDelete == null) {
                // we can't find the old row, it may have been deleted already, but we'll have to update the
                // index anyway in the calling method
//                ctx.success(mutation);
                return false;
            }
            if(keepState)
                this.indexToMainMutationMap.put(indexDelete,mutation);
            if (LOG.isTraceEnabled())
                SpliceLogUtils.trace(LOG, "performing index delete on row %s", BytesUtil.toHex(indexDelete.getRowKey()));
            ensureBufferReader(indexDelete, ctx);
            indexBuffer.add(indexDelete);
        } catch (Exception e) {
            ctx.failed(mutation, WriteResult.failed(e.getClass().getSimpleName()+":"+e.getMessage()));
            return false;
        }
        return true;
    }

    private boolean ensureBufferReader(KVPair mutation, WriteContext ctx) {
        if (indexBuffer == null) {
            try {
                indexBuffer = getWriteBuffer(ctx, expectedWrites);
            } catch (Exception e) {
                ctx.failed(mutation, WriteResult.failed(e.getClass().getSimpleName() + ":" + e.getMessage()));
                return false;
            }
        }
        return true;
    }
}
