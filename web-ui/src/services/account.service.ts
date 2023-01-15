import axios from "axios";
import {Account} from "@/model/Account";
import {reactive} from "vue";
import {ElMessage} from "element-plus";

class AccountService {

  account = reactive(new Account())

  getToken() {
    return localStorage.getItem("token") || ''
  }

  getInfo() {
    const token = localStorage.getItem("token") || ''
    this.account.username = localStorage.getItem("username") || ''
    this.account.authenticated = !!token
  }

  update(account: Account) {
    axios.post("/accounts/update", account).then(() => {
      this.account.username = account.username
      localStorage.setItem('username', account.username)
      ElMessage.success('账号更新成功')
    }, ({response}) => {
      ElMessage.error(response.data.message)
    })
  }

  login(account: Account) {
    this.account.username = account.username
    localStorage.setItem('username', account.username)

    return axios.post("/accounts/login", account).then(({data}) => {
      this.account.authenticated = true
      localStorage.setItem("token", data.token)
      return data
    })
  }

  logout() {
    this.account.authenticated = false
    localStorage.removeItem("token")
  }
}

const accountService = new AccountService()

export default accountService
