<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api, me, setToken, subscribe, token, type CockpitEvent } from './api'
import Dashboard from './views/Dashboard.vue'
import Inbox from './views/Inbox.vue'
import Audit from './views/Audit.vue'
import Settings from './views/Settings.vue'

type View = 'dashboard' | 'inbox' | 'audit' | 'settings'
const views: { id: View; label: string }[] = [
  { id: 'dashboard', label: 'Dashboard' },
  { id: 'inbox', label: 'Inbox' },
  { id: 'audit', label: 'Audit log' },
  { id: 'settings', label: 'Settings' },
]

const view = ref<View>((location.hash.replace('#', '') as View) || 'dashboard')
const pending = ref(0)
const draft = ref('')
const loginError = ref('')
const toast = ref('')
const lastEvent = ref<CockpitEvent | null>(null)
let unsubscribe: (() => void) | null = null

function navigate(target: View) {
  view.value = target
  location.hash = target
}

async function connect() {
  if (!token.value) { me.value = null; return }
  try {
    me.value = await api.me()
    loginError.value = ''
    pending.value = (await api.inboxCounts()).PENDING ?? 0
    unsubscribe?.()
    unsubscribe = subscribe((event) => {
      lastEvent.value = event
      if (event.type.startsWith('inbox.')) api.inboxCounts().then((c) => (pending.value = c.PENDING ?? 0)).catch(() => {})
    })
  } catch (e: any) {
    me.value = null
    loginError.value = e.status === 401 ? 'That token was not accepted.' : `Could not reach the cockpit: ${e.message}`
  }
}

function login() {
  setToken(draft.value)
  connect()
}

function logout() {
  setToken('')
  me.value = null
  unsubscribe?.()
}

function showError(message: string) {
  toast.value = message
  setTimeout(() => (toast.value = ''), 5000)
}

onMounted(() => {
  window.addEventListener('hashchange', () => (view.value = (location.hash.replace('#', '') as View) || 'dashboard'))
  connect()
})
onUnmounted(() => unsubscribe?.())
watch(token, () => { if (!token.value) me.value = null })
</script>

<template>
  <div v-if="!me" class="login panel">
    <h1>Alter2 Cockpit</h1>
    <p class="muted">Paste an API token. The owner token is in <code>data/cockpit/owner.token</code> next to the cockpit's database.</p>
    <form class="row" @submit.prevent="login">
      <input v-model="draft" type="password" placeholder="token" style="flex: 1" autofocus />
      <button class="primary" type="submit">Open</button>
    </form>
    <p v-if="loginError" class="error" style="color: var(--danger)">{{ loginError }}</p>
  </div>

  <div v-else class="layout">
    <aside class="sidebar">
      <div class="brand">Alter2 Cockpit</div>
      <nav>
        <a v-for="v in views" :key="v.id" :href="'#' + v.id" :class="{ active: view === v.id }" @click.prevent="navigate(v.id)">
          {{ v.label }}
          <span v-if="v.id === 'inbox' && pending" class="badge">{{ pending }}</span>
        </a>
      </nav>
      <div class="who">
        {{ me.label }} · {{ me.role.toLowerCase() }}<br />
        <a href="#" @click.prevent="logout">forget token</a>
      </div>
    </aside>
    <main class="main">
      <Dashboard v-if="view === 'dashboard'" :me="me" :last-event="lastEvent" @error="showError" />
      <Inbox v-else-if="view === 'inbox'" :me="me" :last-event="lastEvent" @error="showError" />
      <Audit v-else-if="view === 'audit'" :last-event="lastEvent" @error="showError" />
      <Settings v-else :me="me" @error="showError" />
    </main>
  </div>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
