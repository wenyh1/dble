package com.actiontech.dble.backend.mysql.nio.handler.transaction.normal.stage;

import com.actiontech.dble.backend.mysql.nio.MySQLConnection;
import com.actiontech.dble.backend.mysql.nio.handler.transaction.TransactionStage;
import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.server.NonBlockingSession;

import java.util.List;

public class RollbackStage implements TransactionStage {

    private NonBlockingSession session;
    private final List<BackendConnection> conns;

    public RollbackStage(NonBlockingSession session, List<BackendConnection> conns) {
        this.session = session;
        this.conns = conns;
    }

    @Override
    public void onEnterStage() {
        for (final BackendConnection conn : conns) {
            conn.getBackendService().rollback();
        }
        session.setDiscard(true);
    }

    @Override
    public TransactionStage next(boolean isFail, String errMsg, byte[] sendData) {
        // clear all resources
        session.clearResources(false);
        if (session.closed()) {
            return null;
        }

        session.setResponseTime(false);
        if (isFail) {
            session.getFrontConnection().write(sendData);
            return null;
        }

        if (sendData != null) {
            session.getPacketId().set(sendData[3]);
        } else {
            sendData = session.getOkByteArray();
        }
        boolean multiStatementFlag = session.getIsMultiStatement().get();
        session.getFrontConnection().write(sendData);
        session.multiStatementNextSql(multiStatementFlag);
        session.clearSavepoint();
        return null;
    }
}