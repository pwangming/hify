import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/index.scss'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 全局中文语言包：ElMessageBox 等组件默认按钮（取消/确定）、表格空数据等文案统一中文
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
