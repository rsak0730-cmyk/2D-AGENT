"""Small deterministic worker tools. No shell, filesystem, browser, or arbitrary network access."""
import ast
import math
import operator
from sqlalchemy import or_
from sqlalchemy.orm import Session
from .models import AgentMemory

TOOL_CATALOG = {
    "math.calculate": "Evaluate a bounded arithmetic expression using numeric literals and + - * / // % **.",
    "text.analyze": "Return character, word, and sentence counts for supplied text.",
    "memory.search": "Search this NOVA owner's saved agent/headquarters notes and return a few matching excerpts.",
}

_BINARY = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
}
_UNARY = {ast.UAdd: operator.pos, ast.USub: operator.neg}


def _number(node, depth=0):
    if depth > 20:
        raise ValueError("Expression is too deeply nested")
    if isinstance(node, ast.Constant) and type(node.value) in (int, float):
        value = node.value
        if isinstance(value, int) and value.bit_length() > 400:
            raise ValueError("Numeric literal is outside the safe range")
    elif isinstance(node, ast.UnaryOp) and type(node.op) in _UNARY:
        value = _UNARY[type(node.op)](_number(node.operand, depth + 1))
    elif isinstance(node, ast.BinOp) and type(node.op) in _BINARY:
        left, right = _number(node.left, depth + 1), _number(node.right, depth + 1)
        if isinstance(node.op, ast.Pow) and (abs(right) > 12 or abs(left) > 1e6):
            raise ValueError("Exponent is outside the safe range")
        value = _BINARY[type(node.op)](left, right)
    else:
        raise ValueError("Only numeric literals and basic arithmetic operators are allowed")
    if type(value) not in (int, float):
        raise ValueError("Only real numeric results are supported")
    if isinstance(value, int) and value.bit_length() > 400:
        raise ValueError("Numeric result is outside the safe range")
    try:
        finite = math.isfinite(float(value))
    except OverflowError:
        finite = False
    if not finite or abs(value) > 1e100:
        raise ValueError("Numeric result is outside the safe range")
    return value


def _calculate(arguments):
    if set(arguments) != {"expression"} or not isinstance(arguments["expression"], str):
        raise ValueError("math.calculate requires an expression string")
    expression = arguments["expression"]
    if not expression.strip() or len(expression) > 300:
        raise ValueError("Expression must contain 1–300 characters")
    try:
        tree = ast.parse(expression, mode="eval")
    except (SyntaxError, RecursionError):
        raise ValueError("Expression syntax is invalid")
    if sum(1 for _ in ast.walk(tree)) > 100:
        raise ValueError("Expression is too complex")
    try:
        result = _number(tree.body)
    except (ZeroDivisionError, OverflowError) as error:
        raise ValueError("Arithmetic operation is not defined or exceeded safe limits") from error
    if isinstance(result, float) and result.is_integer():
        result = int(result)
    return {"expression": expression, "result": result}


def execute_safe_tool(name: str, arguments: dict, *, db: Session | None = None, owner_id: str | None = None, agent_id: str | None = None) -> dict:
    """Execute only explicit built-ins. Invalid or unknown requests fail closed."""
    if not isinstance(arguments, dict):
        raise ValueError("Tool arguments must be an object")
    if name == "math.calculate":
        return _calculate(arguments)
    if name == "text.analyze":
        if set(arguments) != {"text"} or not isinstance(arguments["text"], str) or len(arguments["text"]) > 12000:
            raise ValueError("text.analyze requires text of at most 12,000 characters")
        text = arguments["text"]
        words = text.split()
        return {"characters": len(text), "words": len(words), "sentences": sum(text.count(mark) for mark in (".", "!", "?"))}
    if name == "memory.search":
        if set(arguments) != {"query"} or not isinstance(arguments["query"], str) or not 2 <= len(arguments["query"].strip()) <= 120:
            raise ValueError("memory.search requires a 2–120 character query")
        if db is None or owner_id is None or agent_id is None:
            raise ValueError("memory.search is unavailable without an owner-scoped agent context")
        query = arguments["query"].strip()
        rows = db.query(AgentMemory).filter(
            AgentMemory.owner_key_id == owner_id,
            or_(AgentMemory.scope == "headquarters", AgentMemory.agent_id == agent_id),
            AgentMemory.content.contains(query, autoescape=True),
        ).order_by(AgentMemory.importance.desc(), AgentMemory.created_at.desc()).limit(5).all()
        return {"matches": [{"scope": row.scope, "agent_id": row.agent_id, "content": row.content[:1200]} for row in rows]}
    raise ValueError("Unknown tool; no external or arbitrary-code tools are available")
