import { useEffect, useRef } from "react";
import { SearchIcon } from "lucide-react";
import { Kbd } from "@/components/ui/kbd";
import { SidebarInput } from "@/components/ui/sidebar";

/** Filters the apps list in place; ⌘K focuses it from anywhere, Esc clears it. */
export function SidebarSearch({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        input.current?.focus();
        input.current?.select();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="relative">
      <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
      <SidebarInput
        ref={input}
        value={value}
        placeholder="Search apps…"
        aria-label="Search apps"
        className="pr-10 pl-8 text-xs md:text-xs"
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            onChange("");
            e.currentTarget.blur();
          }
        }}
      />
      {!value && <Kbd className="pointer-events-none absolute top-1/2 right-2 -translate-y-1/2">⌘K</Kbd>}
    </div>
  );
}
