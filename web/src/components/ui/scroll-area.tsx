import { ScrollArea as ScrollAreaPrimitive } from "@base-ui/react/scroll-area"
import { cn } from "cn"

type Orientation = "vertical" | "horizontal" | "both"

/**
 * The app's scroll container: native scrollbars are hidden and an overlay bar is drawn on top of the content,
 * so there is never a gutter, and the bar only appears while scrolling or when pointed at.
 *
 * `vertical` (default) keeps content at the area's width; `horizontal` / `both` let it grow wider (wide tables).
 * Children go through ScrollArea.Content so overflow is re-measured as content loads or changes size.
 */
function ScrollArea({
  className,
  children,
  orientation = "vertical",
  viewportClassName,
  contentClassName,
  ...props
}: ScrollAreaPrimitive.Root.Props & {
  orientation?: Orientation
  viewportClassName?: string
  contentClassName?: string
}) {
  const vertical = orientation !== "horizontal"
  const horizontal = orientation !== "vertical"
  return (
    <ScrollAreaPrimitive.Root
      data-slot="scroll-area"
      className={cn("relative flex min-h-0 flex-col", className)}
      {...props}
    >
      <ScrollAreaPrimitive.Viewport
        data-slot="scroll-area-viewport"
        className={cn(
          "size-full max-h-[inherit] min-h-0 rounded-[inherit] transition-[color,box-shadow] outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-1",
          !horizontal && "overflow-x-hidden!",
          !vertical && "overflow-y-hidden!",
          viewportClassName
        )}
      >
        <ScrollAreaPrimitive.Content
          data-slot="scroll-area-content"
          className={contentClassName}
          // Base UI sizes content to fit-content so it can scroll sideways; a vertical-only area keeps it to its width.
          style={horizontal ? undefined : { minWidth: 0 }}
        >
          {children}
        </ScrollAreaPrimitive.Content>
      </ScrollAreaPrimitive.Viewport>
      {vertical && <ScrollBar orientation="vertical" />}
      {horizontal && <ScrollBar orientation="horizontal" />}
      {vertical && horizontal && <ScrollAreaPrimitive.Corner />}
    </ScrollAreaPrimitive.Root>
  )
}

function ScrollBar({
  className,
  orientation = "vertical",
  ...props
}: ScrollAreaPrimitive.Scrollbar.Props) {
  return (
    <ScrollAreaPrimitive.Scrollbar
      data-slot="scroll-area-scrollbar"
      data-orientation={orientation}
      orientation={orientation}
      className={cn(
        "z-10 flex touch-none p-px select-none data-horizontal:h-2.5 data-horizontal:flex-col data-vertical:h-full data-vertical:w-2.5",
        // Hidden at rest: shows while scrolling or when the pointer is on the bar, then fades after a beat.
        "opacity-0 transition-opacity delay-700 duration-300 hover:opacity-100 hover:delay-0 data-scrolling:opacity-100 data-scrolling:delay-0 data-scrolling:duration-100",
        className
      )}
      {...props}
    >
      <ScrollAreaPrimitive.Thumb
        data-slot="scroll-area-thumb"
        className="relative flex-1 rounded-full bg-foreground/25 hover:bg-foreground/40"
      />
    </ScrollAreaPrimitive.Scrollbar>
  )
}

export { ScrollArea, ScrollBar }
