"use client";

import { useEffect, useRef } from "react";
import { basicSetup } from "codemirror";
import { Compartment, EditorState } from "@codemirror/state";
import { EditorView, keymap } from "@codemirror/view";
import { indentWithTab } from "@codemirror/commands";
import { HighlightStyle, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { autocompletion, completeFromList, type Completion } from "@codemirror/autocomplete";
import { groovy } from "@codemirror/legacy-modes/mode/groovy";
import { tags } from "@lezer/highlight";

// A Code step's script editor: CodeMirror with Groovy highlighting, the step's input names
// offered as completions, and Tab indenting (Esc, then Tab, moves on). Loaded on demand (see
// step-config.tsx), so pages without a Code step never download it.

const theme = EditorView.theme(
  {
    "&": {
      backgroundColor: "var(--color-surface-sunken)",
      color: "var(--color-text)",
      fontSize: "12px",
      borderRadius: "0.5rem",
    },
    "&.cm-focused": { outline: "none" },
    ".cm-scroller": {
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
      minHeight: "10rem",
      maxHeight: "28rem",
      overflow: "auto",
      lineHeight: "1.6",
    },
    ".cm-content": { padding: "10px 0", caretColor: "var(--color-lemon)" },
    ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--color-lemon)" },
    ".cm-gutters": {
      backgroundColor: "transparent",
      color: "var(--color-text-faint)",
      border: "none",
      paddingLeft: "4px",
    },
    ".cm-activeLine": { backgroundColor: "rgba(255,255,255,0.03)" },
    ".cm-activeLineGutter": { backgroundColor: "transparent", color: "var(--color-text-muted)" },
    "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection": {
      backgroundColor: "rgba(234,255,60,0.18)",
    },
    ".cm-matchingBracket": { backgroundColor: "rgba(234,255,60,0.15)", outline: "none" },
    ".cm-tooltip": {
      backgroundColor: "var(--color-surface-raised)",
      border: "1px solid var(--color-border-strong)",
      borderRadius: "0.5rem",
    },
    ".cm-tooltip-autocomplete > ul > li[aria-selected]": {
      backgroundColor: "rgba(255,255,255,0.08)",
      color: "var(--color-text)",
    },
    ".cm-panels": { backgroundColor: "var(--color-surface-raised)", color: "var(--color-text)" },
    ".cm-foldPlaceholder": { backgroundColor: "transparent", border: "none", color: "var(--color-text-muted)" },
  },
  { dark: true }
);

const highlight = HighlightStyle.define([
  { tag: [tags.keyword, tags.modifier, tags.controlKeyword], color: "#c792ea" },
  { tag: [tags.string, tags.special(tags.string)], color: "#c3e88d" },
  { tag: [tags.number, tags.bool, tags.null, tags.atom], color: "#f78c6c" },
  { tag: [tags.comment, tags.lineComment, tags.blockComment], color: "var(--color-text-faint)", fontStyle: "italic" },
  { tag: [tags.variableName, tags.propertyName], color: "var(--color-text)" },
  { tag: [tags.typeName, tags.className], color: "#ffcb6b" },
  { tag: [tags.operator, tags.punctuation], color: "var(--color-text-muted)" },
  { tag: tags.meta, color: "#82aaff" },
]);

const GROOVY_WORDS = ["def", "return", "if", "else", "for", "in", "while", "true", "false", "null", "println"].map(
  (w): Completion => ({ label: w, type: "keyword" })
);

function completions(variables: string[]) {
  return autocompletion({
    override: [
      completeFromList([
        ...variables.map((v): Completion => ({ label: v, type: "variable", detail: "input" })),
        ...GROOVY_WORDS,
      ]),
    ],
  });
}

export default function CodeEditor({
  value,
  onChange,
  variables = [],
  readOnly = false,
  invalid = false,
  label,
}: {
  value: string;
  onChange?: (value: string) => void;
  // The step's input names, offered as completions.
  variables?: string[];
  readOnly?: boolean;
  invalid?: boolean;
  label: string;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const completion = useRef(new Compartment());
  const editable = useRef(new Compartment());

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  // Created once; later prop changes are applied to it below.
  useEffect(() => {
    if (!host.current) return;
    const v = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          keymap.of([indentWithTab]),
          StreamLanguage.define(groovy),
          syntaxHighlighting(highlight),
          theme,
          completion.current.of(completions(variables)),
          editable.current.of([EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)]),
          EditorView.contentAttributes.of({ "aria-label": label }),
          EditorView.updateListener.of((u) => {
            if (u.docChanged) onChangeRef.current?.(u.state.doc.toString());
          }),
        ],
      }),
    });
    view.current = v;
    return () => {
      v.destroy();
      view.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A value set from outside (a different step selected, a reset) replaces the text.
  useEffect(() => {
    const v = view.current;
    if (v && v.state.doc.toString() !== value) {
      v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value } });
    }
  }, [value]);

  const names = variables.join("\n");
  useEffect(() => {
    view.current?.dispatch({
      effects: completion.current.reconfigure(completions(names ? names.split("\n") : [])),
    });
  }, [names]);

  useEffect(() => {
    view.current?.dispatch({
      effects: editable.current.reconfigure([EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)]),
    });
  }, [readOnly]);

  return (
    <div
      ref={host}
      data-code-editor
      className={
        "overflow-hidden rounded-lg border transition-colors focus-within:border-lemon " +
        (invalid ? "border-red-500/60" : "border-border-strong")
      }
    />
  );
}
