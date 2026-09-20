"use client";

import { createContext, useContext } from "react";

type CanvasActions = {
  interactive: boolean;
  onConfigure: (nodeId: string) => void;
  onDelete: (nodeId: string) => void;
  onQuickAdd: (parentId: string) => void;
  onInsertNode: (edgeId: string) => void;
};

export const CanvasActionsContext = createContext<CanvasActions>({
  interactive: false,
  onConfigure: () => {},
  onDelete: () => {},
  onQuickAdd: () => {},
  onInsertNode: () => {},
});

export function useCanvasActions() {
  return useContext(CanvasActionsContext);
}
