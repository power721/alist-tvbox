package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class QrCode {
    private String url;
    private String image;
    @JsonProperty("qrcode_key")
    private String qrcodeKey;
}
