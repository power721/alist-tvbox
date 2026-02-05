import { createFetch } from "ofetch";
import accountService from "@/services/account.service";
import router from "@/router";
import { ElMessage } from "element-plus";

const fetchConfig = createFetch({
  defaults: {
    onRequest({ options }) {
      const token = accountService.getToken();
      if (token) {
        options.headers = new Headers(options.headers);
        options.headers.set("Authorization", token);
      }
    },
    onResponseError({ response }) {
      if (response.status === 401) {
        accountService.logout();
        // 避免重复跳转到登录页
        if (router.currentRoute.value.path !== "/login") {
          router.push("/login?redirect=" + router.currentRoute.value.path);
        }
      }
      const data = response._data;
      if (data?.message || data?.detail) {
        console.debug(data.message);
        ElMessage({
          showClose: true,
          grouping: true,
          message: data.message || data.detail,
          type: "error",
        });
      }
      return Promise.reject(data);
    },
  },
});

export const api = {
  get: <T = any>(url: string, params?: any) =>
    fetchConfig<T>(url, { method: "GET", query: params }),
  post: <T = any>(url: string, body?: any) => fetchConfig<T>(url, { method: "POST", body }),
  put: <T = any>(url: string, body?: any) => fetchConfig<T>(url, { method: "PUT", body }),
  delete: <T = any>(url: string, params?: any) =>
    fetchConfig<T>(url, { method: "DELETE", query: params }),
};
