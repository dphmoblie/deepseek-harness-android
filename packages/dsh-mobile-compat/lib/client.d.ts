import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
import { type EngineStoreHandle } from '@deepseek-ai/dsh-client-runtime/client';
import type { PropsRenderSlots, PropsRuntime, PropsStore } from '@deepseek-ai/dsh-client-ui-slots';
export declare const MOBILE_BREAKPOINT = 640;
export declare const SIDEBAR_AUTO_COLLAPSE = 1024;
export type LayoutState = {
    sidebar: number;
    details: number;
    narrow: boolean;
    narrowExpanded: boolean;
    mobile: boolean;
    drawerOpen: boolean;
    sheetOpen: boolean;
};
export type LayoutActions = {
    setSidebar: (draft: LayoutState, px: number) => void;
    setDetails: (draft: LayoutState, px: number) => void;
    toggleSidebar: (draft: LayoutState) => void;
    setNarrow: (draft: LayoutState, narrow: boolean) => void;
    setMobile: (draft: LayoutState, mobile: boolean) => void;
    openDetails: (draft: LayoutState) => void;
    closeDetails: (draft: LayoutState) => void;
};
export declare function createLayoutStore(): EngineStoreHandle<LayoutState, LayoutActions>;
export interface Columns {
    sidebar: number;
    center: number;
    details: number;
}
export declare function computeColumns(viewport: number, sidebar: number, details: number): Columns;
export type AppFrameProps = PropsRuntime<'root'> & PropsRenderSlots<'sidebar' | 'conversation' | 'details' | 'shell.overlay'> & PropsStore<ReturnType<typeof createLayoutStore>>;
export declare function AppFrame({ useStore, actions, renderSlot }: AppFrameProps): import("react").DetailedReactHTMLElement<{
    className: string;
    'data-mobile': string;
    ref: import("react").RefObject<HTMLDivElement>;
}, HTMLDivElement>;
export declare function apply(ctx: ClientContext): () => void;
/** Required Cordis services. The client loader reads this as plugin metadata. */
export declare const inject: string[];
