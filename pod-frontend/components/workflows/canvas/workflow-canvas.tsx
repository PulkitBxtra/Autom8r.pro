"use client";

import { useCallback, useMemo } from "react";
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  BackgroundVariant,
  Controls,
  addEdge,
  type Connection,
  type NodeChange,
  type EdgeChange,
  type OnConnect,
  type OnEdgesChange,
  type OnNodesChange,
} from "@xyflow/react";
import { GraphNode } from "./graph-node";
import { GraphEdge } from "./graph-edge";
import { PlaceholderNode as PlaceholderNodeComponent } from "./placeholder-node";
import { PlaceholderEdge } from "./placeholder-edge";
import { CanvasActionsContext } from "./canvas-actions-context";
import {
  createActionNodeId,
  nextChildSlot,
  NODE_WIDTH,
  PLACEHOLDER_WIDTH,
  TRIGGER_NODE_ID,
} from "@/lib/workflow-graph";
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
  const onConnect: OnConnect = useCallback(
    (connection: Connection) => {
      setEdges((eds) => addEdge({ ...connection, type: "workflowEdge" }, eds));
    },
    [setEdges]
  );

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
      setNodes((nds) => nds.filter((n) => n.id !== id));
      setEdges((eds) => eds.filter((e) => e.id !== id && e.source !== id && e.target !== id));
      if (id === selectedNodeId) onSelectNode(null);
    },
    [interactive, setNodes, setEdges, selectedNodeId, onSelectNode]
  );

  const onQuickAdd = useCallback(
    (sourceId: string) => {
      if (!interactive) return;
      const sourceNode = nodes.find((n) => n.id === sourceId);
      if (!sourceNode) return;

      const newId = createActionNodeId();
      const newNode: WorkflowNode = {
        id: newId,
        type: "workflowNode",
        position: nextChildSlot(sourceNode, edges),
        data: { kind: "action" },
      };
      const newEdge: WorkflowEdge = {
        id: `edge-${sourceId}-${newId}`,
        source: sourceId,
        target: newId,
        type: "workflowEdge",
      };

      setNodes((nds) => [...nds, newNode]);
      setEdges((eds) => [...eds, newEdge]);
      onSelectNode(newId);
    },
    [interactive, nodes, edges, setNodes, setEdges, onSelectNode]
  );

  // Splits an existing connection in two around a freshly created node,
  // so a branch can grow a step in the middle without deleting/redrawing.
  const onInsertNode = useCallback(
    (edgeId: string) => {
      if (!interactive) return;
      const edge = edges.find((e) => e.id === edgeId);
      if (!edge) return;
      const sourceNode = nodes.find((n) => n.id === edge.source);
      const targetNode = nodes.find((n) => n.id === edge.target);
      if (!sourceNode || !targetNode) return;

      const newId = createActionNodeId();
      const newNode: WorkflowNode = {
        id: newId,
        type: "workflowNode",
        position: {
          x: (sourceNode.position.x + targetNode.position.x) / 2,
          y: (sourceNode.position.y + targetNode.position.y) / 2,
        },
        data: { kind: "action" },
      };

      setNodes((nds) => [...nds, newNode]);
      setEdges((eds) => [
        ...eds.filter((e) => e.id !== edgeId),
        { id: `edge-${edge.source}-${newId}`, source: edge.source, target: newId, type: "workflowEdge" },
        { id: `edge-${newId}-${edge.target}`, source: newId, target: edge.target, type: "workflowEdge" },
      ]);
      onSelectNode(newId);
    },
    [interactive, edges, nodes, setNodes, setEdges, onSelectNode]
  );

  // A dangling "add a step" affordance, shown only on true leaf nodes (no
  // outgoing edge yet) -- a node that already has a child stays a plain,
  // single line to it instead of also growing a branch invite. Computed
  // fresh each render, never persisted.
  const placeholders = useMemo(() => {
    if (!interactive) return { nodes: [] as PlaceholderNode[], edges: [] as WorkflowEdge[] };
    const sourceIds = new Set(edges.map((e) => e.source));
    const phNodes: PlaceholderNode[] = [];
    const phEdges: WorkflowEdge[] = [];
    for (const node of nodes) {
      if (sourceIds.has(node.id)) continue;
      const slot = nextChildSlot(node, edges);
      const phId = `placeholder-${node.id}`;
      phNodes.push({
        id: phId,
        type: "placeholderNode",
        // Centered under the node: the real card is NODE_WIDTH wide, the
        // placeholder button is PLACEHOLDER_WIDTH wide, so their top-left
        // x has to differ by half the gap or the connecting line bends.
        position: {
          x: slot.x + (NODE_WIDTH - PLACEHOLDER_WIDTH) / 2,
          y: slot.y,
        },
        data: { parentId: node.id },
        draggable: false,
        selectable: false,
        // Non-selectable/non-draggable nodes get pointer-events: none by
        // default in xyflow -- override it so the add-step button is clickable.
        style: { pointerEvents: "all" },
      });
      phEdges.push({
        id: `placeholder-edge-${node.id}`,
        source: node.id,
        target: phId,
        type: "placeholderEdge",
      });
    }
    return { nodes: phNodes, edges: phEdges };
  }, [nodes, edges, interactive]);

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
    () => ({ interactive, selectedNodeId, onConfigure, onDelete, onQuickAdd, onInsertNode }),
    [interactive, selectedNodeId, onConfigure, onDelete, onQuickAdd, onInsertNode]
  );

  // Memoized so unrelated re-renders (e.g. the panel opening) don't hand
  // ReactFlow a brand-new array reference and trigger it to redo internal
  // measurement/layout work every time.
  const canvasNodes: CanvasNode[] = useMemo(
    () => [...nodes, ...placeholders.nodes],
    [nodes, placeholders.nodes]
  );
  const canvasEdges: WorkflowEdge[] = useMemo(
    () => [...edges, ...placeholders.edges],
    [edges, placeholders.edges]
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
            onConnect={onConnect}
            nodeTypes={NODE_TYPES}
            edgeTypes={EDGE_TYPES}
            nodesDraggable={interactive}
            nodesConnectable={interactive}
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
