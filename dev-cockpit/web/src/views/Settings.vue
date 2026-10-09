<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type ApiToken, type Principal } from '../api'

const props = defineProps<{ me: Principal }>()
const emit = defineEmits<{ error: [message: string] }>()

const tokens = ref<ApiToken[]>([])
const role = ref('dev')
const label = ref('')
const issued = ref('')

async function load() {
  if (props.me.role !== 'OWNER') return
  try {
    tokens.value = await api.tokens()
  } catch (e: any) {
    emit('error', e.message)
  }
}

async function issue() {
  try {
    const result = await api.issueToken(role.value, label.value)
    issued.value = result.token
    label.value = ''
    await load()
  } catch (e: any) {
    emit('error', e.message)
  }
}

async function revoke(token: ApiToken) {
  if (!confirm(`Revoke the ${token.role.toLowerCase()} token "${token.label}"?`)) return
  try {
    await api.revokeToken(token.id)
    await load()
  } catch (e: any) {
    emit('error', e.message)
  }
}

onMounted(load)
</script>

<template>
  <h1>Settings</h1>
  <div class="panel">
    <h2>You</h2>
    <p>{{ me.label }} · <code>{{ me.role.toLowerCase() }}</code> (token #{{ me.tokenId }})</p>
    <p class="muted">
      Viewers read everything. Devs also decide inbox cards and control the server. Owners also manage tokens.
    </p>
  </div>

  <div v-if="me.role === 'OWNER'" class="panel">
    <h2>API tokens</h2>
    <form class="row" @submit.prevent="issue">
      <select v-model="role">
        <option value="viewer">viewer</option>
        <option value="dev">dev</option>
        <option value="owner">owner</option>
      </select>
      <input v-model="label" placeholder="label (who or what uses it)" required style="flex: 1" />
      <button class="primary" type="submit">Issue</button>
    </form>
    <p v-if="issued">
      New token, shown once: <code>{{ issued }}</code>
    </p>
    <table style="margin-top: .75rem">
      <thead><tr><th>#</th><th>Label</th><th>Role</th><th>Created</th><th>Last used</th><th></th></tr></thead>
      <tbody>
        <tr v-for="t in tokens" :key="t.id">
          <td>{{ t.id }}</td>
          <td>{{ t.label }}</td>
          <td><code>{{ t.role.toLowerCase() }}</code></td>
          <td class="muted">{{ t.createdAt.slice(0, 10) }}</td>
          <td class="muted">{{ t.lastUsedAt ? t.lastUsedAt.slice(0, 16).replace('T', ' ') : 'never' }}</td>
          <td><button v-if="t.id !== me.tokenId" class="danger" @click="revoke(t)">Revoke</button></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
