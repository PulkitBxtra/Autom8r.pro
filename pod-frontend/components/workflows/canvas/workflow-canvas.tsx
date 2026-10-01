"use client";

import { useCallback, useMemo } from "react";
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  BackgroundVariant,
  Controls,
  type NodeChange,
  type EdgeChange,
  type OnEdgesChange,
  type OnNodesChange,
} from "@xyflow/react";
import { GraphNode } from "./graph-node";
import { GraphEdge } from "./graph-edge";
import { PlaceholderNode as PlaceholderNodeComponent } from "./placeholder-node";
import { PlaceholderEdge } from "./placeholder-edge";
import { CanvasActionsContext } from "./canvas-actions-context";
import { createActionNodeId, edgeId, TRIGGER_NODE_ID } from "@/lib/workflow-graph";
import { layoutWorkflow, PLACEHOLDER_WIDTH } from "@/lib/workflow-layout";
import type {
  CanvasNode,
  PlaceholderNode,
  WorkflowEdge,
  WorkflowNode,
} from "@/lib/workflow-graph";

const NODE_TYPES = { workflowNode: GraphNode, placeholderNode: PlaceholderNodeComponent };
const EDGE_TYPES = { workflowEdge: GraphEdge, placeholderEdge: PlaceholderEdge };

export function WorkflowCanvas({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  setNodes,
  setEdges,
  interactive,
  selectedNodeId,
  onSelectNode,
}: {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  onNodesChange: OnNodesChange<WorkflowNode>;
  onEdgesChange: OnEdgesChange<WorkflowEdge>;
  setNodes: React.Dispatch<React.SetStateAction<WorkflowNode[]>>;
  setEdges: React.Dispatch<React.SetStateAction<WorkflowEdge[]>>;
  interactive: boolean;
  selectedNodeId: string | null;
  onSelectNode: (nodeId: string | null) => void;
}) {
  const layout = useMemo(() => layoutWorkflow(nodes, edges), [nodes, edges]);

  // Opening a step to view/edit it is never destructive, so this works the
  // same whether the canvas is interactive or read-only.
  const onConfigure = useCallback(
    (nodeId: string) => {
      onSelectNode(nodeId);
    },
    [onSelectNode]
  );

  const onDelete = useCallback(
    (id: string) => {
      if (!interactive) return;
      if (id === TRIGGER_NODE_ID) return;
      // A step with one way on (any plain step, or a Logic step whose paths are all empty) is
      // cut out and the steps either side are joined, keeping the chain. A Logic step with
      // steps on its paths can't be merged into one line, so what hung off them is left
      // unconnected (saving points it out).
      const next = new Set(edges.filter((e) => e.source === id).map((e) => e.target));
      setNodes((nds) => nds.filter((n) => n.id !== id));
      setEdges((eds) => {
        const kept = eds.filter((e) => e.source !== id && e.target !== id);
        if (next.size > 1) return kept;
        for (const into of eds.filter((e) => e.target === id)) {
          for (const target of next) {
            const joined = edgeId(into.source, target, into.sourceHandle);
            if (kept.some((e) => e.id === joined)) continue;
            kept.push({
              id: joined,
              source: into.source,
              sourceHandle: into.sourceHandle ?? null,
              target,
              type: "workflowEdge",
            });
          }
        }
        return kept;
      });
      if (id === selectedNodeId) onSelectNode(null);
    },
    [interactive, edges, setNodes, setEdges, selectedNodeId, onSelectNode]
  );

  // Where it goes is up to the layout.
  const newStep = useCallback(
    (): WorkflowNode => ({
      id: createActionNodeId(nodes.map((n) => n.id)),
      type: "workflowNode",
      position: { x: 0, y: 0 },
      data: { kind: "action" },
    }),
    [nodes]
  );

  const onQuickAdd = useCallback(
    (sourceId: string, handle?: string) => {
      if (!interactive) return;
      if (!nodes.some((n) => n.id === sourceId)) return;
      const node = newStep();
      setNodes((nds) => [...nds, node]);
      setEdges((eds) => [
        ...eds,
        {
          id: edgeId(sourceId, node.id, handle),
          source: sourceId,
          sourceHandle: handle ?? null,
          target: node.id,
          type: "workflowEdge",
        },
      ]);
      onSelectNode(node.id);
    },
    [interactive, nodes, newStep, setNodes, setEdges, onSelectNode]
  );

  // Adds the step a Logic step's paths meet at: every open end of its paths leads to it.
  const onAddMerge = useCallback(
    (blockId: string) => {
      if (!interactive) return;
      const node = newStep();
      const joins = layout.openEndsOf(blockId).map(
        (end): WorkflowEdge => ({
          id: edgeId(end.source, node.id, end.handle),
          source: end.source,
          sourceHandle: end.handle ?? null,
          target: node.id,
          type: "workflowEdge",
        })
      );
      setNodes((nds) => [...nds, node]);
      setEdges((eds) => [...eds, ...joins]);
      onSelectNode(node.id);
    },
    [interactive, layout, newStep, setNodes, setEdges, onSelectNode]
  );

  // Splits an existing connection in two around a freshly created node, so a branch can grow
  // a step in the middle; the layout then moves everything below it down a row.
  const onInsertNode = useCallback(
    (id: string) => {
      if (!interactive) return;
      const edge = edges.find((e) => e.id === id);
      if (!edge) return;
      const node = newStep();
      setNodes((nds) => [...nds, node]);
      setEdges((eds) => [
        ...eds.filter((e) => e.id !== id),
        // The new step takes the old edge's place under a Logic step's output.
        {
          id: edgeId(edge.source, node.id, edge.sourceHandle),
          source: edge.source,
          sourceHandle: edge.sourceHandle ?? null,
          target: node.id,
          type: "workflowEdge",
        },
        { id: edgeId(node.id, edge.target), source: node.id, target: edge.target, type: "workflowEdge" },
      ]);
      onSelectNode(node.id);
    },
    [interactive, edges, newStep, setNodes, setEdges, onSelectNode]
  );

  const realNodeIds = useMemo(() => new Set(nodes.map((n) => n.id)), [nodes]);
  const realEdgeIds = useMemo(() => new Set(edges.map((e) => e.id)), [edges]);

  const handleNodesChange = useCallback(
    (changes: NodeChange<CanvasNode>[]) => {
      const relevant = changes.filter((c) => "id" in c && realNodeIds.has(c.id));
      if (relevant.length > 0) onNodesChange(relevant as NodeChange<WorkflowNode>[]);
    },
    [onNodesChange, realNodeIds]
  );

  const handleEdgesChange = useCallback(
    (changes: EdgeChange<WorkflowEdge>[]) => {
      const relevant = changes.filter((c) => "id" in c && realEdgeIds.has(c.id));
      if (relevant.length > 0) onEdgesChange(relevant);
    },
    [onEdgesChange, realEdgeIds]
  );

  const actions = useMemo(
    () => ({ interactive, selectedNodeId, onConfigure, onDelete, onQuickAdd, onAddMerge, onInsertNode }),
    [interactive, selectedNodeId, onConfigure, onDelete, onQuickAdd, onAddMerge, onInsertNode]
  );

  // Memoized so unrelated re-renders (e.g. the panel opening) don't hand
  // ReactFlow a brand-new array reference and trigger it to redo internal
  // measurement/layout work every time.
  // Steps can't be dragged, so xyflow would give them pointer-events: none on a read-only
  // canvas and swallow clicks meant to open a step; keep them clickable.
  // Add-step buttons and pending lines only show while editing.
  const canvasNodes: CanvasNode[] = useMemo(
    () => [
      ...nodes.map((n) => ({
        ...n,
        position: layout.positions.get(n.id) ?? n.position,
        style: { ...n.style, pointerEvents: "all" as const },
      })),
      ...(interactive
        ? layout.placeholders.map(
            (p): PlaceholderNode => ({
              id: p.id,
              type: "placeholderNode",
              position: { x: p.x - PLACEHOLDER_WIDTH / 2, y: p.y },
              data: { parentId: p.parentId, handle: p.handle, mergeOf: p.mergeOf },
              draggable: false,
              selectable: false,
              // Non-selectable/non-draggable nodes get pointer-events: none by
              // default in xyflow -- override it so the add-step button is clickable.
              style: { pointerEvents: "all" },
            })
          )
        : []),
    ],
    [nodes, layout, interactive]
  );
  const canvasEdges: WorkflowEdge[] = useMemo(
    () => [
      ...edges.map((e) => ({ ...e, data: { ...e.data, route: layout.routes.get(e.id) } })),
      ...(interactive
        ? layout.ghostEdges.map(
            (g): WorkflowEdge => ({
              id: g.id,
              source: g.source,
              sourceHandle: g.sourceHandle,
              target: g.target,
              type: "placeholderEdge",
              data: { route: g.route, add: g.add },
            })
          )
        : []),
    ],
    [edges, layout, interactive]
  );

  return (
    <CanvasActionsContext.Provider value={actions}>
      <ReactFlowProvider>
        <div className="h-full w-full">
          <ReactFlow
            nodes={canvasNodes}
            edges={canvasEdges}
            onNodesChange={handleNodesChange}
            onEdgesChange={handleEdgesChange}
            nodeTypes={NODE_TYPES}
            edgeTypes={EDGE_TYPES}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={interactive}
            panOnDrag={false}
            panOnScroll
            zoomOnScroll={false}
            zoomOnPinch
            fitView
            fitViewOptions={{ padding: 0.4, maxZoom: 1 }}
            minZoom={0.4}
            maxZoom={1.5}
          >
            <Background
              variant={BackgroundVariant.Dots}
              gap={22}
              size={1.4}
              color="var(--color-border-strong)"
            />
            <Controls showInteractive={false} position="bottom-right" />
          </ReactFlow>
        </div>
      </ReactFlowProvider>
    </CanvasActionsContext.Provider>
  );
}
