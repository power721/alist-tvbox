import {createRouter, createWebHashHistory} from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import SitesView from "@/views/SitesView.vue";
import ConfigView from "@/views/VodView.vue";
import SubView from "@/views/SubView.vue";
import AboutView from "@/views/AboutView.vue";
import IndexView from "@/views/IndexView.vue";
import LoginView from "@/views/LoginView.vue";
import UserView from "@/views/UserView.vue";

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  linkActiveClass: 'is-active',
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView
    },
    {
      path: '/sites',
      name: 'sites',
      component: SitesView,
      meta: {auth: true}
    },
    {
      path: '/index',
      name: 'index',
      component: IndexView,
      meta: {auth: true}
    },
    {
      path: '/user',
      name: 'user',
      component: UserView,
      meta: {auth: true}
    },
    {
      path: '/sub/:id',
      name: 'sub',
      component: SubView
    },
    {
      path: '/vod',
      name: 'vod',
      component: ConfigView,
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: {guest: true}
    },
    {
      path: '/about',
      name: 'about',
      component: AboutView
    }
  ]
})

export default router
