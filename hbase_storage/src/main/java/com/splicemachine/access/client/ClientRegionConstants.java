package com.splicemachine.access.client;

import com.splicemachine.primitives.Bytes;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;

/**
 * @author Scott Fines
 *         Date: 1/6/16
 */
public class ClientRegionConstants{
    final static byte[] FLUSH = Bytes.toBytes("F");
    final static byte[] HOLD = Bytes.toBytes("H");
    public final static String SPLICE_SCAN_MEMSTORE_ONLY="MR";
    final public static String SPLICE_SCAN_MEMSTORE_PARTITION_BEGIN_KEY="PTBK";
    final public static String SPLICE_SCAN_MEMSTORE_PARTITION_END_KEY="PTEK";
    final public static String SPLICE_SCAN_MEMSTORE_PARTITION_SERVER="PTS";
    final public static KeyValue MEMSTORE_BEGIN = new KeyValue(HConstants.EMPTY_START_ROW,HOLD,HOLD,0l, new byte[0]);
//    final public static KeyValue MEMSTORE_END = new KeyValue(HConstants.EMPTY_END_ROW,HOLD,HOLD);
    final public static KeyValue MEMSTORE_BEGIN_FLUSH = new KeyValue(HConstants.EMPTY_START_ROW,FLUSH,FLUSH, 0l, new byte[0]);
}