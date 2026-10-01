"use client";

import { createContext, useContext } from "react";

type CanvasActions = {
  interactive: boolean;
  selectedNodeId: string | null;
  onConfigure: (nodeId: string) => void;
  onDelete: (nodeId: string) => void;
  // handle: the Logic step output to add the step under.
  onQuickAdd: (parentId: string, handle?: string) => void;
  // Adds the step a Logic step's paths meet at.
  onAddMerge: (blockId: string) => void;
  onInsertNode: (edgeId: string) => void;
};

export const CanvasActionsContext = createContext<CanvasActions>({
  interactive: false,
  selectedNodeId: null,
  onConfigure: () => {},
  onDelete: () => {},
  onQuickAdd: () => {},
  onAddMerge: () => {},
  onInsertNode: () => {},
});

export function useCanvasActions() {
  return useContext(CanvasActionsContext);
}
