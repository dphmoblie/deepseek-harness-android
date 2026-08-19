/**
 * dsh-mobile-compat — browser half.
 *
 * Registers a mobile-aware AppFrame into the built-in 'root' slot (declaring
 * the same four child seats as the shipped desktop layout) and injects
 * mobile CSS overrides for desktop plugin panels. Below MOBILE_BREAKPOINT
 * (640px): sidebar -> overlay drawer, details -> bottom sheet, center ->
 * full width, plus a top bar. Desktop widths keep the classic three-column
 * concession solver, so the same package is safe in a browser profile.
 *
 * Contract notes (verified against the shipped rc.6 type surface and the
 * @dsh-android/dsh-client-ui-responsive compiled output): root registration
 * is { name, children, store, inject }; the store seat takes the FACTORY
 * (framework instantiates per entry); AppFrame receives the composed
 * PropsRuntime / PropsRenderSlots / PropsStore share.
 */
import { createElement as h, useEffect, useRef, useState } from 'react'
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { defineStore, type EngineStoreHandle } from '@deepseek-ai/dsh-client-runtime/client'
import type { PropsRenderSlots, PropsRuntime, PropsStore } from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-layout/client'

// ===== layout store =====

export const MOBILE_BREAKPOINT = 640
export const SIDEBAR_AUTO_COLLAPSE = 1024
const CENTER_MIN = 640
const SIDEBAR_MIN = 264
const SIDEBAR_MAX = 420
const SIDEBAR_DEFAULT = 280
const SIDEBAR_COLLAPSED = 56
const DETAILS_MIN = 300
const DETAILS_MAX = 520
const DETAILS_DEFAULT = 380

export type LayoutState = {
  sidebar: number
  details: number
  narrow: boolean
  narrowExpanded: boolean
  mobile: boolean
  drawerOpen: boolean
  sheetOpen: boolean
}

export type LayoutActions = {
  setSidebar: (draft: LayoutState, px: number) => void
  setDetails: (draft: LayoutState, px: number) => void
  toggleSidebar: (draft: LayoutState) => void
  setNarrow: (draft: LayoutState, narrow: boolean) => void
  setMobile: (draft: LayoutState, mobile: boolean) => void
  openDetails: (draft: LayoutState) => void
  closeDetails: (draft: LayoutState) => void
}

const clamp = (px: number, min: number, max: number) => Math.min(max, Math.max(min, Math.round(px)))

export function createLayoutStore(): EngineStoreHandle<LayoutState, LayoutActions> {
  return defineStore<LayoutState, LayoutActions>({
    init: () => ({
      sidebar: SIDEBAR_DEFAULT,
      details: 0,
      narrow: false,
      narrowExpanded: false,
      mobile: false,
      drawerOpen: false,
      sheetOpen: false,
    }),
    actions: {
      setSidebar: (draft, px) => { draft.sidebar = clamp(px, SIDEBAR_MIN, SIDEBAR_MAX) },
      setDetails: (draft, px) => { draft.details = clamp(px, DETAILS_MIN, DETAILS_MAX) },
      toggleSidebar: (draft) => {
        if (draft.mobile) draft.drawerOpen = !draft.drawerOpen
        else if (draft.narrow) draft.narrowExpanded = !draft.narrowExpanded
        else draft.sidebar = draft.sidebar === 0 ? SIDEBAR_DEFAULT : 0
      },
      setNarrow: (draft, narrow) => { draft.narrow = narrow },
      setMobile: (draft, mobile) => { draft.mobile = mobile },
      openDetails: (draft) => {
        if (draft.mobile) draft.sheetOpen = true
        else draft.details = draft.details === 0 ? DETAILS_DEFAULT : draft.details
      },
      closeDetails: (draft) => {
        draft.sheetOpen = false
        draft.details = 0
      },
    },
  })
}

// ===== column solver =====

export interface Columns { sidebar: number; center: number; details: number }

export function computeColumns(viewport: number, sidebar: number, details: number): Columns {
  if (viewport < MOBILE_BREAKPOINT) return { sidebar: 0, center: viewport, details: 0 }
  const s = sidebar === 0 ? SIDEBAR_COLLAPSED : clamp(sidebar, SIDEBAR_MIN, SIDEBAR_MAX)
  const d0 = details === 0 ? 0 : clamp(details, DETAILS_MIN, DETAILS_MAX)
  if (s + d0 + CENTER_MIN <= viewport) return { sidebar: s, center: viewport - s - d0, details: d0 }
  const d1 = d0 === 0 ? 0 : Math.max(DETAILS_MIN, viewport - s - CENTER_MIN)
  if (s + d1 + CENTER_MIN <= viewport) return { sidebar: s, center: CENTER_MIN, details: d1 }
  return { sidebar: s, center: Math.max(0, viewport - s), details: 0 }
}

// ===== CSS =====

const FRAME_CSS = `
.dsh-mobile-frame{position:relative;display:flex;height:100%;overflow:hidden;background:var(--dsw-alias-bg-base,inherit)}
.dsh-mobile-frame[data-mobile="true"]{flex-direction:column}
.dsh-mobile-sidebar{flex:none;overflow:hidden}
.dsh-mobile-center{flex:1 1 auto;min-width:0;display:flex;flex-direction:column;overflow:hidden}
.dsh-mobile-details{flex:none;overflow:hidden}
.dsh-mobile-drawer{position:fixed;top:0;bottom:0;left:0;z-index:40;width:min(84vw,340px);
  transform:translateX(-100%);transition:transform var(--ds-transition-duration-slow,180ms) var(--ds-ease-in-out,ease);
  padding-top:env(safe-area-inset-top);padding-bottom:env(safe-area-inset-bottom)}
.dsh-mobile-drawer[data-open="true"]{transform:translateX(0)}
.dsh-mobile-scrim{position:fixed;inset:0;z-index:39;background:rgba(0,0,0,.4)}
.dsh-mobile-sheet{position:fixed;left:0;right:0;bottom:0;z-index:40;max-height:70vh;
  transform:translateY(100%);transition:transform var(--ds-transition-duration-slow,180ms) var(--ds-ease-in-out,ease);
  padding-bottom:env(safe-area-inset-bottom);border-radius:16px 16px 0 0;overflow:hidden}
.dsh-mobile-sheet[data-open="true"]{transform:translateY(0)}
.dsh-mobile-topbar{flex:none;display:flex;align-items:center;gap:8px;height:48px;padding:0 8px;
  padding-top:env(safe-area-inset-top)}
.dsh-mobile-handle{flex:none;height:4px;width:36px;border-radius:2px;margin:8px auto 0;opacity:.5}
`

const PANEL_CSS = `
@media (max-width: 640px) {
  [data-plugin*="aionui"] [data-panel="explorer"],
  [data-plugin*="aionui"] [data-panel="preview"] { max-width: 100vw !important; }
  .dsh-mobile-frame[data-mobile="true"] [data-draggable-handle] { display: none; }
  [data-plugin*="task-board"] [data-board-columns] { display: flex; overflow-x: auto; scroll-snap-type: x mandatory; }
  [data-plugin*="task-board"] [data-board-column] { scroll-snap-align: start; min-width: 88vw; }
  [data-plugin*="market"] [data-card-grid] { grid-template-columns: 1fr !important; }
}
`

// ===== AppFrame =====

export type AppFrameProps = PropsRuntime<'root'> &
  PropsRenderSlots<'sidebar' | 'conversation' | 'details' | 'shell.overlay'> &
  PropsStore<ReturnType<typeof createLayoutStore>>

export function AppFrame({ useStore, actions, renderSlot }: AppFrameProps) {
  const state = useStore((s) => s)
  const frameRef = useRef<HTMLDivElement>(null)
  const [viewport, setViewport] = useState<number>(() => (typeof window === 'undefined' ? 1024 : window.innerWidth))

  useEffect(() => {
    const measure = () => {
      const width = frameRef.current?.clientWidth ?? window.innerWidth
      setViewport(width)
      actions.setNarrow(width < SIDEBAR_AUTO_COLLAPSE)
      actions.setMobile(width < MOBILE_BREAKPOINT)
    }
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [actions])

  const mobile = viewport < MOBILE_BREAKPOINT
  const cols = computeColumns(viewport, mobile ? 0 : state.sidebar, mobile ? 0 : state.details)
  const drawerOpen = mobile ? state.drawerOpen : (state.sidebar !== 0 || state.narrowExpanded)
  const sheetOpen = mobile ? state.sheetOpen : state.details !== 0
  const sidebarOwner = {
    collapsed: !mobile && !drawerOpen,
    width: mobile ? 340 : cols.sidebar,
  }

  return h('div', { className: 'dsh-mobile-frame', 'data-mobile': mobile ? 'true' : 'false', ref: frameRef },
    mobile && h('div', { className: 'dsh-mobile-topbar' },
      h('button', { type: 'button', onClick: () => actions.toggleSidebar(), 'aria-label': 'menu' }, '☰'),
      h('button', { type: 'button', onClick: () => actions.openDetails(), 'aria-label': 'details' }, '⋯'),
    ),
    mobile && drawerOpen && h('div', { className: 'dsh-mobile-scrim', onClick: () => actions.toggleSidebar() }),
    h('aside', {
      className: mobile ? 'dsh-mobile-drawer' : 'dsh-mobile-sidebar',
      'data-open': drawerOpen ? 'true' : 'false',
      style: mobile ? undefined : { width: cols.sidebar },
    }, renderSlot('sidebar', sidebarOwner)),
    h('main', { className: 'dsh-mobile-center', style: { width: cols.center } },
      renderSlot('conversation', {}),
      renderSlot('shell.overlay', {}),
    ),
    !mobile && h('aside', {
      className: 'dsh-mobile-details',
      'data-open': sheetOpen ? 'true' : 'false',
      style: { width: cols.details },
    }, renderSlot('details', {})),
    mobile && h('div', { className: 'dsh-mobile-sheet', 'data-open': sheetOpen ? 'true' : 'false' },
      h('div', { className: 'dsh-mobile-handle' }),
      renderSlot('details', {}),
    ),
  )
}

// ===== plugin entry =====

export function apply(ctx: ClientContext): () => void {
  ctx.effect(() => {
    const disposeRegistration = ctx.slots.register(
      {
        name: 'root',
        children: {
          sidebar: { kind: 'single', scope: 'root' },
          conversation: { kind: 'single', scope: 'session-maybe' },
          details: { kind: 'single', scope: 'session' },
          'shell.overlay': { kind: 'list', scope: 'root' },
        },
        store: createLayoutStore,
        inject: () => ({}),
      },
      AppFrame,
    )
    return () => { disposeRegistration() }
  }, 'dsh-mobile-compat: root registration')

  ctx.effect(() => {
    const style = document.createElement('style')
    style.setAttribute('data-plugin', 'dsh-mobile-compat')
    style.textContent = FRAME_CSS + PANEL_CSS
    document.head.appendChild(style)
    return () => { style.remove() }
  }, 'dsh-mobile-compat: styles')

  return () => {}
}

/** Required Cordis services. The client loader reads this as plugin metadata. */
export const inject = ['slots']
