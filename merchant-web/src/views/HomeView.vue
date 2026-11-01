<template>
  <section class="entry-hero">
    <p class="eyebrow">TOKEOUT · 每一餐，都有人用心</p>
    <h1>从一份好吃的开始<span>，<br>让生活慢慢有滋味。</span></h1>
    <p class="entry-description">想吃的、用心做的、正在送的。在这里，各就各位。</p>
    <div class="entry-cards">
      <router-link to="/customer/shops" class="entry-card customer-entry"><span class="entry-index">01 / DISCOVER</span><span class="entry-icon" aria-hidden="true">碗</span><h2>今天想吃什么</h2><p>发现用心好店，选好这一餐。</p><span class="entry-cta">进入顾客端 <span>↗</span></span></router-link>
      <router-link to="/merchant/orders" class="entry-card"><span class="entry-index">02 / PREPARE</span><span class="entry-icon" aria-hidden="true">厨</span><h2>好好做好每一单</h2><p>查看订单，从接单到出餐。</p><span class="entry-cta">进入商家端 <span>↗</span></span></router-link>
      <router-link to="/rider/tasks" class="entry-card"><span class="entry-index">03 / DELIVER</span><span class="entry-icon" aria-hidden="true">送</span><h2>把热乎的送到</h2><p>寻找任务，从出发到送达。</p><span class="entry-cta">进入骑手端 <span>↗</span></span></router-link>
    </div>
    <p class="entry-note">顾客可直接浏览店铺 · 三端使用各自账号登录</p>
  </section>
</template>
<style scoped>
.entry-hero{padding:35px 0 20px}.entry-hero>h1{font-size:clamp(32px,4.1vw,58px);line-height:1.4;letter-spacing:-1.5px;margin:20px 0}.entry-hero>h1>span{font-weight:400;color:#657768}.entry-description{color:var(--color-muted);line-height:1.9;margin-bottom:38px}.entry-cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px}.entry-card{display:flex;flex-direction:column;padding:30px;text-decoration:none;color:var(--color-ink);background:#fff;border:1px solid var(--color-border);border-radius:20px;transition:transform .2s,border-color .2s}.entry-card:hover{transform:translateY(-4px);border-color:#92ae98}.entry-index{font-size:10px;letter-spacing:1.8px;color:var(--color-muted)}.entry-icon{margin:30px 0 8px;width:60px;height:60px;display:grid;place-items:center;border-radius:50%;background:#edf1e7;font-family:serif;font-size:28px;color:var(--color-primary)}.entry-card h2{font-size:23px;margin:15px 0 8px}.entry-card p{color:var(--color-muted);font-size:14px;line-height:1.8}.entry-cta{border-top:1px solid var(--color-border);margin-top:28px;padding-top:20px;font-size:14px;font-weight:600;display:flex;justify-content:space-between}.customer-entry{background:var(--color-primary);color:#fff}.customer-entry .entry-index,.customer-entry p{color:#d4e2d4}.customer-entry .entry-cta{border-color:#ffffff30}.entry-note{text-align:center;color:var(--color-muted);font-size:12px;margin-top:28px}@media(max-width:760px){.entry-hero{padding-top:10px}.entry-cards{grid-template-columns:1fr;gap:16px}.entry-card{padding:24px}.entry-icon{margin:18px 0 0;width:48px;height:48px;font-size:23px}.entry-card h2{font-size:21px}.entry-cta{margin-top:14px}.entry-hero>h1{letter-spacing:-.8px}}
</style>
