package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.DriverAccount;

public class BaiduNetdisk extends Storage {
    public BaiduNetdisk(DriverAccount account) {
        super(account, "BaiduNetdisk");
        addAddition("refresh_token", account.getToken());
        addAddition("root_folder_path", account.getFolder());
        addAddition("order_by", "name");
        addAddition("order_direction", "asc");
        addAddition("client_id", "iYCeC9g08h5vuP9UqvPHKKSVrKFXGa1v");
        addAddition("client_secret", "jXiFMOPVPCWlO2M5CwWQzffpNPaGTRBG");
        addAddition("custom_crack_ua", "netdisk");
        addAddition("download_api", "crack_video");
        addAddition("only_list_video_file", true);
        buildAddition();
    }
}
