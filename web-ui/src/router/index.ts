import {createRouter, createWebHashHistory} from 'vue-router'
import HomeView from '../views/HomeView.vue'
import SitesView from "@/views/SitesView.vue";
import ConfigView from "@/views/VodView.vue";
import SubView from "@/views/SubView.vue";

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
      component: SitesView
    },
    {
      path: '/sub',
      name: 'sub',
      component: SubView
    },
    {
      path: '/vod',
      name: 'vod',
      component: ConfigView
    },
    {
      path: '/about',
      name: 'about',
      // route level code-splitting
      // this generates a separate chunk (About.[hash].js) for this route
      // which is lazy-loaded when the route is visited.
      component: () => import('../views/AboutView.vue')
    }
  ]
})

export default router
