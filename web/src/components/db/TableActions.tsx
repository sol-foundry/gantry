import { useState } from "react";
import { EraserIcon, MoreHorizontalIcon, PencilIcon, Trash2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import type { RequestDdl } from "./format";

interface Props {
  label: string;
  kind: string;
  base: { schema: string; table: string };
  onDdl: RequestDdl;
}

/** Rename / Truncate / Drop for one table; views can only be dropped. Destructive ones are confirmed in the SQL review. */
function useTableActions({ label, kind, base, onDdl }: Props) {
  const [renaming, setRenaming] = useState(false);
  return {
    isTable: kind === "table",
    rename: () => setRenaming(true),
    truncate: () => onDdl(`Truncate ${label}`, { op: "truncate_table", ...base }, `truncate ${base.table}`),
    drop: () => onDdl(`Drop ${label}`, { op: "drop_table", ...base }, `drop ${kind} ${base.table}`),
    dialog: renaming && (
      <RenameDialog
        label={label}
        current={base.table}
        onClose={() => setRenaming(false)}
        onRename={(newName) => {
          setRenaming(false);
          onDdl(`Rename ${label}`, { op: "rename_table", ...base, newName }, `rename table ${base.table} to ${newName}`);
        }}
      />
    ),
  };
}

/** Header buttons of the table view. */
export function TableActionButtons(props: Props) {
  const actions = useTableActions(props);
  return (
    <>
      {actions.isTable && (
        <>
          <Button variant="outline" size="sm" onClick={actions.rename}>
            <PencilIcon />
            Rename
          </Button>
          <Button variant="outline" size="sm" onClick={actions.truncate}>
            <EraserIcon />
            Truncate
          </Button>
        </>
      )}
      <Button variant="destructive" size="sm" onClick={actions.drop}>
        <Trash2Icon />
        Drop
      </Button>
      {actions.dialog}
    </>
  );
}

/** Row menu of the database overview. */
export function TableActionMenu(props: Props) {
  const actions = useTableActions(props);
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="ghost" size="icon-sm" aria-label={`Actions for ${props.label}`} />}>
          <MoreHorizontalIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-40">
          {actions.isTable && (
            <>
              <DropdownMenuItem onClick={actions.rename}>
                <PencilIcon />
                Rename
              </DropdownMenuItem>
              <DropdownMenuItem onClick={actions.truncate}>
                <EraserIcon />
                Truncate
              </DropdownMenuItem>
              <DropdownMenuSeparator />
            </>
          )}
          <DropdownMenuItem variant="destructive" onClick={actions.drop}>
            <Trash2Icon />
            Drop
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {actions.dialog}
    </>
  );
}

function RenameDialog({ label, current, onClose, onRename }: { label: string; current: string; onClose: () => void; onRename: (name: string) => void }) {
  const [name, setName] = useState(current);
  const valid = name.trim() !== "" && name.trim() !== current;
  const submit = () => valid && onRename(name.trim());
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Rename {label}</DialogTitle>
          <DialogDescription>You&apos;ll see the SQL before anything changes.</DialogDescription>
        </DialogHeader>
        <Input
          autoFocus
          className="font-mono"
          value={name}
          aria-label="New table name"
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()}
        />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!valid} onClick={submit}>
            Review SQL
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
