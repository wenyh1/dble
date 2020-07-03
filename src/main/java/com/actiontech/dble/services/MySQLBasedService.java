package com.actiontech.dble.services;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.config.model.user.UserConfig;
import com.actiontech.dble.net.connection.AbstractConnection;
import com.actiontech.dble.net.mysql.CharsetNames;
import com.actiontech.dble.net.service.AbstractService;
import com.actiontech.dble.net.service.ServiceTask;
import com.actiontech.dble.route.parser.util.Pair;

import java.nio.ByteBuffer;

/**
 * Created by szf on 2020/6/28.
 */
public abstract class MySQLBasedService extends AbstractService {

    protected UserConfig userConfig;

    protected Pair<String, String> user;

    protected long clientFlags;


    public MySQLBasedService(AbstractConnection connection) {
        super(connection);
    }


    protected void TaskToPriorityQueue(ServiceTask task) {
        DbleServer.getInstance().getFrontPriorityQueue().offer(task);
    }

    protected void TaskToTotalQueue(ServiceTask task) {
        DbleServer.getInstance().getFrontHandlerQueue().offer(task);
    }


    @Override
    public void handleData(ServiceTask task) {
        ServiceTask executeTask = null;
        synchronized (this) {
            if (currentTask == null) {
                executeTask = taskQueue.poll();
                if (executeTask != null) {
                    currentTask = executeTask;
                }
            }
            if (currentTask != task) {
                TaskToPriorityQueue(task);
            }
        }

        if (executeTask != null) {
            byte[] data = executeTask.getOrgData();
            this.setPacketId(data[3]);
            this.handleInnerData(data);
        }
    }

    protected abstract void handleInnerData(byte[] data);


    public UserConfig getUserConfig() {
        return userConfig;
    }

    public CharsetNames getCharset() {
        return connection.getCharsetName();
    }





}
