package com.splicemachine.derby.utils.marshall;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;

import java.io.IOException;

/**
 * @author Scott Fines
 * Date: 11/18/13
 */
public class NoOpKeyHashDecoder implements KeyHashDecoder {
		public static final NoOpKeyHashDecoder INSTANCE = new NoOpKeyHashDecoder();

		private NoOpKeyHashDecoder() { }
		@Override public void set(byte[] bytes, int hashOffset, int length) { }
		@Override public void decode(ExecRow destination) throws StandardException { }

		@Override public void close() throws IOException {  }
}
