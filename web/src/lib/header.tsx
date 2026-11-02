import { createContext, useContext, type ReactNode } from "react";
import { createPortal } from "react-dom";

/** Where pages put their own content in the top bar: the current page's crumb, and actions on the right. */
export interface HeaderTargets {
  crumb: HTMLElement | null;
  actions: HTMLElement | null;
}

export const HeaderContext = createContext<HeaderTargets>({ crumb: null, actions: null });

/** Renders [children] into a top-bar slot; nothing until the bar has mounted. */
export function HeaderSlot({ slot, children }: { slot: keyof HeaderTargets; children: ReactNode }) {
  const target = useContext(HeaderContext)[slot];
  return target ? createPortal(children, target) : null;
}
