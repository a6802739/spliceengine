package com.splicemachine.derby.utils.marshall;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.kvpair.KVPair;

import java.io.IOException;

/**
 * @author Scott Fines
 *         Date: 11/15/13
 */
public class PairDecoder{
    private final KeyDecoder keyDecoder;
    private final KeyHashDecoder rowDecoder;
    private final ExecRow templateRow;

    public PairDecoder(KeyDecoder keyDecoder,
                       KeyHashDecoder rowDecoder,
                       ExecRow templateRow){
        this.keyDecoder=keyDecoder;
        this.rowDecoder=rowDecoder;
        this.templateRow=templateRow;
    }

    public ExecRow decode(KVPair kvPair) throws StandardException{
        templateRow.resetRowArray();
        keyDecoder.decode(kvPair.getRowKey(),0,kvPair.getRowKey().length,templateRow);
        rowDecoder.set(kvPair.getValue(),0,kvPair.getValue().length);
        rowDecoder.decode(templateRow);
        return templateRow;
    }

    public ExecRow decode(byte[] key, byte[] value) throws StandardException{
        templateRow.resetRowArray();
        keyDecoder.decode(key,0,key.length,templateRow);
        rowDecoder.set(value,0,value.length);
        rowDecoder.decode(templateRow);
        return templateRow;

    }


		/*
         *
		 *  < a | b |c >
		 *    1 | 2 | 3
		 *
		 *  sort (a) -->
		 *  Row Key: 1
		 *  Row Data: 2 | 3
		 *
		 *  group (a,b) ->
		 *  Row Key: a | b
		 *  Row Data: aggregate(c)
		 */

    public int getKeyPrefixOffset(){
        return keyDecoder.getPrefixOffset();
    }

    public ExecRow getTemplate(){
        return templateRow;
    }

    @Override
    public String toString(){
        return String.format("PairDecoder { keyDecoder=%s rowDecoder=%s, templateRow=%s}",keyDecoder,rowDecoder,templateRow);
    }

    public void close() throws IOException{
        try{
            if(keyDecoder!=null)
                keyDecoder.close();
        }finally{
            if(rowDecoder!=null)
                rowDecoder.close();
        }
    }

}
