package cn.har01d.alist_tvbox.live.model;

import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.TarsOutputStream;
import com.qq.tars.protocol.tars.TarsStructBase;
import com.qq.tars.protocol.tars.support.TarsMethodInfo;
import com.qq.tars.protocol.util.TarsHelper;
import com.qq.tars.rpc.protocol.tars.TarsServantRequest;
import com.qq.tars.rpc.protocol.tup.UniAttribute;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class HuyaWup extends TarsStructBase {

    public final TarsServantRequest tarsServantRequest;
    public final UniAttribute uniAttribute;

    public HuyaWup() {
        this.tarsServantRequest = new TarsServantRequest(null);
        this.tarsServantRequest.setMethodInfo(new TarsMethodInfo());
        this.uniAttribute = new UniAttribute();
    }

    @Override
    public void writeTo(TarsOutputStream out) {
        out.write(TarsHelper.VERSION3, 1);
        out.write(tarsServantRequest.getPacketType(), 2);
        out.write(tarsServantRequest.getMessageType(), 3);
        out.write(tarsServantRequest.getRequestId(), 4);
        out.write(tarsServantRequest.getServantName(), 5);
        out.write(tarsServantRequest.getFunctionName(), 6);
        out.write(uniAttribute.encode(), 7);
        out.write(tarsServantRequest.getTimeout(), 8);
        out.write(tarsServantRequest.getContext(), 9);
        out.write(tarsServantRequest.getStatus(), 10);
    }

    @Override
    public void readFrom(TarsInputStream ins) {
        tarsServantRequest.setVersion(ins.read(tarsServantRequest.getVersion(), 1, false));
        tarsServantRequest.setPacketType(ins.read(tarsServantRequest.getPacketType(), 2, false));
        tarsServantRequest.setMessageType(ins.read(tarsServantRequest.getMessageType(), 3, false));
        tarsServantRequest.setRequestId(ins.read(tarsServantRequest.getRequestId(), 4, false));
        tarsServantRequest.setServantName(ins.read(tarsServantRequest.getServantName(), 5, false));
        tarsServantRequest.setFunctionName(ins.read(tarsServantRequest.getFunctionName(), 6, false));

        byte[] bytes = ins.read(new byte[0], 7, false);
        uniAttribute.decode(bytes);

        tarsServantRequest.setTimeout(ins.read(tarsServantRequest.getTimeout(), 8, false));

        Map<String, String> context = ins.readMap(tarsServantRequest.getContext(), 9, false);
        tarsServantRequest.setContext(context);

        Map<String, String> status = ins.readMap(tarsServantRequest.getStatus(), 10, false);
        tarsServantRequest.setStatus(status);
    }

    public byte[] encode() {
        TarsOutputStream outStream = new TarsOutputStream();
        outStream.setServerEncoding(StandardCharsets.UTF_8.name());
        this.writeTo(outStream);

        byte[] serializedData = outStream.toByteArray();
        int length = 4 + serializedData.length;

        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.putInt(length);
        buffer.put(serializedData);

        return buffer.array();
    }

    public void decode(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int length;
        try {
            length = buffer.getInt();
        } catch (Exception e) {
            return;
        }

        if (length < 4) {
            return;
        }

        byte[] bytes = new byte[length - 4];
        buffer.get(bytes);

        TarsInputStream ins = new TarsInputStream(bytes);
        ins.setServerEncoding(StandardCharsets.UTF_8.name());
        this.readFrom(ins);
    }
}
