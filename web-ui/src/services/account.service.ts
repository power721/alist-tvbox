import { api } from "@/services/api";
import { Account } from "@/model/Account";
import { reactive } from "vue";
import { ElMessage } from "element-plus";

class AccountService {
  account = reactive(new Account());

  getToken() {
    return localStorage.getItem("token") || "";
  }

  getInfo() {
    const token = localStorage.getItem("token") || "";
    this.account.username = localStorage.getItem("username") || "";
    this.account.authenticated = !!token;
  }

  update(account: Account) {
    api.post("/api/accounts/update", account).then((data) => {
      this.account.username = account.username;
      localStorage.setItem("username", account.username);
      this.account.authenticated = true;
      localStorage.setItem("token", data.token);
      ElMessage.success("账号更新成功");
    });
  }

  login(account: Account) {
    this.account.username = account.username;
    localStorage.setItem("username", account.username);

    return api.post("/api/accounts/login", account).then((data) => {
      this.account.authenticated = true;
      localStorage.setItem("token", data.token);
      return data;
    });
  }

  logout() {
    this.account.authenticated = false;
    localStorage.removeItem("token");
    return api.post("/api/accounts/logout");
  }
}

const accountService = new AccountService();

export default accountService;
