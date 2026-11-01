import { defineConfig, mergeConfig } from 'vite'
import appConfig from '../vite.config.js'

// A serve-only device substitute for browser QA; never included in production.
export default defineConfig(({ command }) => {
  if (command !== 'serve' || process.env.TAKEOUT_UI_QA_FIXTURE !== '1') {
    throw new Error('QA configuration requires TAKEOUT_UI_QA_FIXTURE=1 and vite serve.')
  }
  return mergeConfig(appConfig, {
    server: { proxy: Object.fromEntries(
      ['/api', '/user', '/cart', '/order', '/rider', '/merchant', '/category', '/uploads', '/images']
        .map((prefix) => [prefix, 'http://127.0.0.1:18080']),
    ) },
    plugins: [{
      name: 'test-only-geolocation',
      transformIndexHtml: {
        order: 'pre',
        handler: () => [{ tag: 'script', injectTo: 'head-prepend', children: `
          (() => {
            const pending = new Map(); let nextId = 1;
            const fix = () => ({coords:{longitude:120.15,latitude:30.27,accuracy:100},timestamp:Date.now()});
            Object.defineProperty(navigator, 'geolocation', {configurable:true,value:{
              watchPosition(success) { const id=nextId++; pending.set(id,setTimeout(()=>success(fix()),20)); return id; },
              clearWatch(id) { clearTimeout(pending.get(id)); pending.delete(id); },
              getCurrentPosition(success) { setTimeout(()=>success(fix()),20); }
            }});
            document.addEventListener('DOMContentLoaded', () => {
              const banner = document.createElement('div');
              banner.textContent = '前端 QA · 内存测试数据 / 模拟定位 · 未读取设备位置';
              banner.style.cssText = 'background:#fff2d6;color:#72531c;text-align:center;font:12px/1.7 sans-serif;padding:6px 12px';
              document.body.prepend(banner);
            });
          })();
        ` }],
      },
    }],
  })
})
