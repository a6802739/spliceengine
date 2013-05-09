package com.splicemachine.derby.impl.job;

import com.splicemachine.utils.SpliceZooKeeperManager;
import com.splicemachine.job.TaskStatus;
import com.splicemachine.si.api.TransactionId;
import com.splicemachine.si.api.Transactor;
import com.splicemachine.si.impl.TransactorFactoryImpl;
import org.apache.hadoop.hbase.regionserver.HRegion;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.concurrent.ExecutionException;

/**
 * @author Scott Fines
 * Created on: 5/3/13
 */
public abstract class TransactionalTask extends ZooKeeperTask{
    private static final long serialVersionUID=2l;
    private String transactionId;
    private String parentTransaction;
    private boolean readOnly;

    protected TransactionalTask() {
        super();
    }

    protected TransactionalTask(String jobId, int priority, String parentTransaction,boolean readOnly) {
        super(jobId, priority);
        this.parentTransaction = parentTransaction;
        this.transactionId = null;
        this.readOnly = readOnly;
    }

    @Override
    public void prepareTask(HRegion region, SpliceZooKeeperManager zooKeeper) throws ExecutionException {
        //check the current state
        try {
            TaskStatus currentStatus = getTaskStatus();
            switch (currentStatus.getStatus()) {
                case INVALID:
                case PENDING:
                    break;
                case EXECUTING:
                case FAILED:
                    rollbackIfNecessary();
                    break;
                case COMPLETED:
                case CANCELLED:
                    return; //nothing to do here, since we've already taken care of things
            }

            //create a new child transaction of the parent transaction
            final Transactor transactor = TransactorFactoryImpl.getTransactor();
            TransactionId id = transactor.beginChildTransaction(
                    transactor.transactionIdFromString(parentTransaction), !readOnly, !readOnly, null, null);
            transactionId = id.getTransactionIdString();
        } catch (IOException e) {
            throw new ExecutionException(e);
        }
        super.prepareTask(region, zooKeeper);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        out.writeUTF(parentTransaction);
        out.writeBoolean(readOnly);
        out.writeBoolean(transactionId!=null);
        if(transactionId!=null)
            out.writeUTF(transactionId);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        parentTransaction = in.readUTF();
        readOnly = in.readBoolean();
        if(in.readBoolean())
            transactionId = in.readUTF();

    }

    private void rollbackIfNecessary() throws IOException {
        if(transactionId==null) return; //nothing to roll back just yet

        final Transactor transactor = TransactorFactoryImpl.getTransactor();
        transactor.rollback(transactor.transactionIdFromString(transactionId));

    }
}
