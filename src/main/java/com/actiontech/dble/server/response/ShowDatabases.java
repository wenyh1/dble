/*
* Copyright (C) 2016-2020 ActionTech.
* based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
* License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
*/
package com.actiontech.dble.server.response;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.mysql.PacketUtil;
import com.actiontech.dble.config.Fields;
import com.actiontech.dble.config.ServerConfig;
import com.actiontech.dble.config.model.user.ShardingUserConfig;
import com.actiontech.dble.config.model.user.UserConfig;
import com.actiontech.dble.config.model.user.UserName;
import com.actiontech.dble.net.mysql.EOFPacket;
import com.actiontech.dble.net.mysql.FieldPacket;
import com.actiontech.dble.net.mysql.ResultSetHeaderPacket;
import com.actiontech.dble.net.mysql.RowDataPacket;
import com.actiontech.dble.server.ServerConnection;
import com.actiontech.dble.services.mysqlsharding.MySQLShardingService;
import com.actiontech.dble.util.StringUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author mycat
 */
public final class ShowDatabases {
    private ShowDatabases() {
    }

    private static final int FIELD_COUNT = 1;
    private static final ResultSetHeaderPacket HEADER = PacketUtil.getHeader(FIELD_COUNT);
    private static final FieldPacket[] FIELDS = new FieldPacket[FIELD_COUNT];
    private static final EOFPacket EOF = new EOFPacket();


    public static void response(MySQLShardingService shardingService) {


        byte packetId = (byte) shardingService.getSession2().getPacketId().get();
        HEADER.setPacketId(++packetId);
        FIELDS[0] = PacketUtil.getField("DATABASE", Fields.FIELD_TYPE_VAR_STRING);
        FIELDS[0].setPacketId(++packetId);
        EOF.setPacketId(++packetId);

        ByteBuffer buffer = shardingService.allocate();
        // write header
        buffer = HEADER.write(buffer, shardingService, true);


        // write fields
        for (FieldPacket field : FIELDS) {
            buffer = field.write(buffer, shardingService, true);
        }

        // write eof
        buffer = EOF.write(buffer, shardingService, true);

        // write rows
        ServerConfig conf = DbleServer.getInstance().getConfig();
        Map<UserName, UserConfig> users = conf.getUsers();
        UserConfig user = users == null ? null : users.get(shardingService.getUser());
        if (user != null) {
            ShardingUserConfig shardingUser = (ShardingUserConfig) user;
            TreeSet<String> schemaSet = new TreeSet<>();
            Set<String> schemaList = shardingUser.getSchemas();
            if (schemaList == null || schemaList.size() == 0) {
                schemaSet.addAll(conf.getSchemas().keySet());
            } else {
                schemaSet.addAll(schemaList);
            }
            for (String name : schemaSet) {
                RowDataPacket row = new RowDataPacket(FIELD_COUNT);
                row.add(StringUtil.encode(name, shardingService.getCharset().getResults()));
                row.setPacketId(++packetId);
                buffer = row.write(buffer, shardingService, true);
            }
        }

        // write last eof
        EOFPacket lastEof = new EOFPacket();
        lastEof.setPacketId(++packetId);
        shardingService.getSession2().multiStatementPacket(lastEof, packetId);
        buffer = lastEof.write(buffer, shardingService, true);
        boolean multiStatementFlag = shardingService.getSession2().getIsMultiStatement().get();
        shardingService.write(buffer);
        shardingService.getSession2().multiStatementNextSql(multiStatementFlag);
    }

}
