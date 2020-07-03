/*
 * Copyright (C) 2016-2020 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.server.response;

import com.actiontech.dble.backend.mysql.PacketUtil;
import com.actiontech.dble.config.Fields;
import com.actiontech.dble.net.connection.AbstractConnection;
import com.actiontech.dble.net.mysql.EOFPacket;
import com.actiontech.dble.net.mysql.FieldPacket;
import com.actiontech.dble.net.mysql.ResultSetHeaderPacket;
import com.actiontech.dble.net.service.AbstractService;
import com.actiontech.dble.server.ServerConnection;

import java.nio.ByteBuffer;


public final class InformationSchemaProfiling {
    private InformationSchemaProfiling() {
    }

    private static final int FIELD_COUNT = 3;
    private static final ResultSetHeaderPacket HEADER = PacketUtil.getHeader(FIELD_COUNT);
    private static final FieldPacket[] FIELDS = new FieldPacket[FIELD_COUNT];
    private static final EOFPacket EOF = new EOFPacket();


    /**
     * response method.
     *
     * @param service
     */
    public static void response(AbstractService service) {


        int i = 0;
        byte packetId = 0;
        HEADER.setPacketId(++packetId);
        FIELDS[i] = PacketUtil.getField("State", Fields.FIELD_TYPE_VAR_STRING);
        FIELDS[i].setPacketId(++packetId);
        FIELDS[i + 1] = PacketUtil.getField("Duration", Fields.FIELD_TYPE_DECIMAL);
        FIELDS[i + 1].setPacketId(++packetId);

        FIELDS[i + 2] = PacketUtil.getField("Percentage", Fields.FIELD_TYPE_DECIMAL);
        FIELDS[i + 2].setPacketId(++packetId);
        EOF.setPacketId(++packetId);
        ByteBuffer buffer = service.allocate();

        // write header
        buffer = HEADER.write(buffer, service, true);

        // write fields
        for (FieldPacket field : FIELDS) {
            buffer = field.write(buffer, service, true);
        }

        // write eof
        buffer = EOF.write(buffer, service, true);

        // write rows
        packetId = EOF.getPacketId();


        // write last eof
        EOFPacket lastEof = new EOFPacket();
        lastEof.setPacketId(++packetId);
        buffer = lastEof.write(buffer, service, true);

        // post write
        service.write(buffer);


    }


}
