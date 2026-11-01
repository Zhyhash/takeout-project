<template>
  <div v-if="loading" class="page-state" role="status" aria-live="polite"><span class="state-symbol">◌</span><p>正在加载，请稍候</p></div>
  <div v-else-if="error" class="page-state" role="alert"><span class="state-symbol">!</span><h2>暂时无法加载</h2><p>{{ error }}</p><el-button @click="$emit('retry')">重新加载</el-button></div>
  <div v-else-if="empty" class="page-state" role="status"><span class="state-symbol">∅</span><h2>{{ title }}</h2><p>{{ description }}</p><slot /></div>
</template>
<script setup>
defineProps({ loading: Boolean, error: String, empty: Boolean, title: { type: String, default: '这里暂时还没有内容' }, description: String })
defineEmits(['retry'])
</script>
