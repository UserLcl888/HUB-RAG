import { createApp, h } from 'vue'
import { createRouter, createWebHistory, RouterView } from 'vue-router'
import App from './App.vue'
import './style.css'
// 白色主题（由 scripts/build-light-theme.mjs 生成，挂 html[data-theme="white"]），必须后于 style.css
import './style-light.css'
// 白色主题精修层（手工维护）：把生成器保留下的蓝灰渐变面压成干净白面
import './style-light-tweaks.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: App },
    { path: '/documents', component: App },
    { path: '/knowledge', component: App },
    { path: '/review', component: App },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.path !== from.path) return { top: 0 }
    return undefined
  },
})

createApp({ render: () => h(RouterView) }).use(router).mount('#app')
