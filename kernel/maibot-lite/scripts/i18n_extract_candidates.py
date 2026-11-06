from __future__ import annotations

from pathlib import Path

import ast
import re

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EXCLUDE_PARTS = {
    ".git",
    ".venv",
    "dashboard",
    "docs",
    "docs-src",
    "locales",
}
HAN_PATTERN = re.compile(r"[\u4e00-\u9fff]")


def should_skip(path: Path) -> bool:
    return any(part in DEFAULT_EXCLUDE_PARTS for part in path.parts)


def iter_python_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*.py") if path.is_file() and not should_skip(path.relative_to(root)))


class CandidateExtractor(ast.NodeVisitor):
    def __init__(self) -> None:
        self._docstring_nodes: set[ast.AST] = set()
        self.candidates: list[tuple[int, str]] = []

    def visit_Module(self, node: ast.Module) -> None:
        self._mark_docstring_node(node)
        self.generic_visit(node)

    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        self._mark_docstring_node(node)
        self.generic_visit(node)

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
        self._mark_docstring_node(node)
        self.generic_visit(node)

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        self._mark_docstring_node(node)
        self.generic_visit(node)

    def visit_Constant(self, node: ast.Constant) -> None:
        if node in self._docstring_nodes:
            return
        if isinstance(node.value, str) and HAN_PATTERN.search(node.value):
            self.candidates.append((node.lineno, node.value.strip()))
        self.generic_visit(node)

    def _mark_docstring_node(self, node: ast.Module | ast.ClassDef | ast.AsyncFunctionDef | ast.FunctionDef) -> None:
        if not node.body:
            return
        first_stmt = node.body[0]
        if isinstance(first_stmt, ast.Expr) and isinstance(first_stmt.value, ast.Constant):
            if isinstance(first_stmt.value.value, str):
                self._docstring_nodes.add(first_stmt.value)


def extract_candidates(file_path: Path) -> list[tuple[int, str]]:
    source = file_path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(file_path))
    extractor = CandidateExtractor()
    extractor.visit(tree)
    return extractor.candidates


def main() -> int:
    for file_path in iter_python_files(PROJECT_ROOT):
        for lineno, text in extract_candidates(file_path):
            print(f"{file_path.relative_to(PROJECT_ROOT)}:{lineno}: {text}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
