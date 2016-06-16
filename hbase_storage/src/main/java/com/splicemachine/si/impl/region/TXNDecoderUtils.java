package com.splicemachine.si.impl.region;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;

import com.google.protobuf.ByteString;
import com.google.protobuf.ZeroCopyLiteralByteString;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.Txn.IsolationLevel;
import com.splicemachine.si.coprocessor.TxnMessage;

public class TXNDecoderUtils {

    public static TxnMessage.Txn composeValue(Cell destinationTables, IsolationLevel level, long txnId, long beginTs,long parentTs,  boolean hasAdditive,
    		boolean additive, long commitTs, long globalCommitTs, Txn.State state, long kaTime) {
        ByteString destTableBuffer = null;
        if(destinationTables!=null){
            destTableBuffer = ZeroCopyLiteralByteString.wrap(CellUtil.cloneValue(destinationTables));
        }
        if (level == null)
        	level = Txn.IsolationLevel.SNAPSHOT_ISOLATION;
        TxnMessage.TxnInfo.Builder info = TxnMessage.TxnInfo.newBuilder().setIsolationLevel(level.encode())
        		.setTxnId(txnId).setBeginTs(beginTs).setParentTxnid(parentTs);
        if (destTableBuffer !=null)
        	info.setDestinationTables(destTableBuffer);
        if(hasAdditive)
            info = info.setIsAdditive(additive);
        return TxnMessage.Txn.newBuilder().setInfo(info.build())
                .setCommitTs(commitTs).setGlobalCommitTs(globalCommitTs).setState(state.getId()).setLastKeepAliveTime(kaTime).build();

    }
}