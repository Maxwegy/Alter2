<script setup lang="ts">
import { computed } from 'vue'

interface Node { type: string; speaker?: string; text?: string; prompt?: string | null; options?: { label: string; body: Node[] }[]; kind?: string; target?: string; body?: Node[] }

const props = defineProps<{ result: Record<string, any> }>()

const facts = computed(() => Object.entries(props.result.facts ?? {}).filter(([, v]) => v !== null && v !== undefined && v !== ''))
const sections = computed(() => (props.result.transcript?.sections ?? []) as { title: string; parent?: string; questDependent: boolean; body: Node[] }[])

function render(nodes: Node[], depth = 0): string[] {
  const pad = '  '.repeat(depth)
  const out: string[] = []
  for (const n of nodes) {
    if (n.type === 'line') out.push(`${pad}${n.speaker}: ${n.text}`)
    else if (n.type === 'options' || n.type === 'random') {
      out.push(`${pad}[${n.type === 'random' ? 'random' : n.prompt ?? 'options'}]`)
      for (const o of n.options ?? []) {
        out.push(`${pad}  > ${o.label}`)
        out.push(...render(o.body, depth + 2))
      }
    } else if (n.type === 'action') out.push(`${pad}(${n.text})`)
    else if (n.type === 'condition') { out.push(`${pad}if ${n.text}`); out.push(...render(n.body ?? [], depth + 1)) }
    else { out.push(`${pad}# ${n.text}`); out.push(...render(n.body ?? [], depth + 1)) }
  }
  return out
}
</script>

<template>
  <div class="enrichment">
    <div v-if="result.page">
      Wiki page: <a :href="result.page.url" target="_blank" rel="noopener">{{ result.page.title }}</a>
      <span class="muted"> · {{ result.kind }} · scaffold: {{ result.target }}</span>
    </div>
    <div v-else class="muted">No wiki page · scaffold: {{ result.target }}</div>
    <ul v-if="result.notes?.length" class="notes">
      <li v-for="(n, i) in result.notes" :key="i">{{ n }}</li>
    </ul>
    <table v-if="facts.length" class="facts">
      <tbody>
        <tr v-for="[k, v] in facts" :key="k"><th>{{ k }}</th><td>{{ Array.isArray(v) ? v.join(', ') : v }}</td></tr>
      </tbody>
    </table>
    <details v-if="sections.length" open>
      <summary>Transcript ({{ sections.length }} section{{ sections.length === 1 ? '' : 's' }})</summary>
      <div v-for="s in sections" :key="s.title + (s.parent ?? '')">
        <div class="muted">{{ s.parent ? s.parent + ' › ' : '' }}{{ s.title }}<span v-if="s.questDependent"> · quest-state dependent</span></div>
        <pre>{{ render(s.body).join('\n') }}</pre>
      </div>
    </details>
    <details v-if="result.shop" open>
      <summary>{{ result.shop.name }} · {{ result.shop.stock.length }} stock lines</summary>
      <table>
        <thead><tr><th>Item</th><th>Stock</th><th>Sells for</th><th>Buys for</th><th>Restock</th></tr></thead>
        <tbody>
          <tr v-for="l in result.shop.stock" :key="l.item"><td>{{ l.item }}</td><td>{{ l.stock }}</td><td>{{ l.sellPrice }}</td><td>{{ l.buyPrice }}</td><td>{{ l.restockTicks }}</td></tr>
        </tbody>
      </table>
    </details>
    <details v-if="result.pickpocket?.length" open>
      <summary>Pickpocket · {{ result.pickpocket.length }} lines</summary>
      <table>
        <thead><tr><th>Item</th><th>Rarity</th><th>Quantity</th><th>Level</th></tr></thead>
        <tbody>
          <tr v-for="l in result.pickpocket" :key="l.item"><td>{{ l.item }}</td><td>{{ l.rarity }}</td><td>{{ l.quantity }}</td><td>{{ l.level ?? '' }}</td></tr>
        </tbody>
      </table>
    </details>
    <details v-if="result.recipes?.length" open>
      <summary>Recipes · {{ result.recipes.length }}</summary>
      <ul>
        <li v-for="r in result.recipes" :key="r.output">
          {{ r.output }}<span v-if="r.outputQuantity"> ×{{ r.outputQuantity }}</span> from
          {{ r.materials.map((m: any) => `${m.second ?? m[1]}× ${m.first ?? m[0]}`).join(', ') }}
          <span v-if="r.facilities?.length"> at {{ r.facilities.join(', ') }}</span>
          <span v-if="r.skill"> · {{ r.skill }} {{ r.level }} ({{ r.experience }} xp)</span>
          <span v-if="r.ticks"> · {{ r.ticks }} ticks</span>
        </li>
      </ul>
    </details>
    <div v-if="result.sources?.length" class="muted sources">
      Sources: <template v-for="(s, i) in result.sources" :key="s.url"><a :href="s.url" target="_blank" rel="noopener">{{ s.title }}</a><span v-if="i < result.sources.length - 1">, </span></template>
      ({{ result.sources[0].license }})
    </div>
  </div>
</template>

<style scoped>
.enrichment { margin-top: .5rem; }
.notes { color: var(--warn); margin: .4rem 0; padding-left: 1.2rem; }
.facts th { width: 8rem; }
.sources { margin-top: .4rem; font-size: 12px; }
pre { max-height: 18rem; }
</style>
