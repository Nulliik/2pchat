import inspect
import re
from pathlib import Path
import pytest

import messenger.discovery_bridge as discovery_bridge
import messenger.core.identity as identity
import messenger.bootstrap as bootstrap


def _extract_kotlin_call_attrs():
    """
    Parse PythonBridge.kt and extract all callAttr("function_name", args...) invocations
    using a balanced parenthesis parser.
    Returns a list of dicts: {"method": str, "arg_count": int, "raw_args": str}
    """
    repo_root = Path(__file__).resolve().parents[2]
    kt_file = repo_root / "2PChat android" / "android" / "app" / "src" / "main" / "java" / "com" / "example" / "twopchat" / "PythonBridge.kt"
    assert kt_file.exists(), f"PythonBridge.kt not found at {kt_file}"

    content = kt_file.read_text(encoding="utf-8")
    
    invocations = []
    idx = 0
    while True:
        pos = content.find("callAttr", idx)
        if pos == -1:
            break
        
        # Find opening paren
        open_paren = content.find("(", pos)
        if open_paren == -1:
            break
        
        # Walk balanced parens
        paren_depth = 0
        in_str = False
        str_char = ''
        close_paren = -1
        
        for i in range(open_paren, len(content)):
            ch = content[i]
            if in_str:
                if ch == '\\':
                    continue
                if ch == str_char:
                    in_str = False
            elif ch in ('"', "'"):
                in_str = True
                str_char = ch
            elif ch == '(':
                paren_depth += 1
            elif ch == ')':
                paren_depth -= 1
                if paren_depth == 0:
                    close_paren = i
                    break
        
        if close_paren != -1:
            inner = content[open_paren + 1:close_paren].strip()
            # First argument is method name (e.g. "send_p2p_message")
            first_comma = -1
            in_s = False
            s_char = ''
            p_depth = 0
            for j in range(len(inner)):
                c = inner[j]
                if in_s:
                    if c == '\\':
                        continue
                    if c == s_char:
                        in_s = False
                elif c in ('"', "'"):
                    in_s = True
                    s_char = c
                elif c in ('(', '[', '{'):
                    p_depth += 1
                elif c in (')', ']', '}'):
                    p_depth -= 1
                elif c == ',' and p_depth == 0 and not in_s:
                    first_comma = j
                    break
            
            if first_comma != -1:
                raw_method = inner[:first_comma].strip().strip('"').strip("'")
                raw_rest = inner[first_comma + 1:].strip()
                arg_count = _count_arguments(raw_rest)
            else:
                raw_method = inner.strip().strip('"').strip("'")
                arg_count = 0
            
            if raw_method and not raw_method.startswith("$"):
                invocations.append({
                    "method": raw_method,
                    "arg_count": arg_count,
                    "raw_args": inner,
                })
            idx = close_paren + 1
        else:
            idx = pos + 8

    return invocations


def _count_arguments(arg_str: str) -> int:
    args = []
    current = []
    paren_depth = 0
    in_quote = False
    quote_char = ''

    for char in arg_str:
        if in_quote:
            current.append(char)
            if char == quote_char:
                in_quote = False
        elif char in ('"', "'"):
            in_quote = True
            quote_char = char
            current.append(char)
        elif char in ('(', '[', '{'):
            paren_depth += 1
            current.append(char)
        elif char in (')', ']', '}'):
            paren_depth -= 1
            current.append(char)
        elif char == ',' and paren_depth == 0:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(char)

    if current and "".join(current).strip():
        args.append("".join(current).strip())

    return len(args)


def test_chaquopy_python_bridge_all_exported_functions_exist():
    """
    Verify that every function invoked via callAttr in Kotlin PythonBridge exists
    in discovery_bridge, identity, or bootstrap.
    """
    calls = _extract_kotlin_call_attrs()
    assert len(calls) >= 20, f"Expected at least 20 callAttr invocations in PythonBridge.kt, found {len(calls)}"

    modules = [discovery_bridge, identity, bootstrap]
    
    missing_methods = []
    for call in calls:
        method = call["method"]
        found = any(hasattr(mod, method) for mod in modules)
        if not found:
            missing_methods.append(method)

    assert not missing_methods, f"Kotlin calls Python methods that DO NOT exist: {missing_methods}"


def test_chaquopy_python_bridge_argument_count_contract():
    """
    Verify that the number of arguments passed from Kotlin matches the Python function parameter signature.
    Prevents TypeError: takes X arguments but Y were given on real devices.
    """
    calls = _extract_kotlin_call_attrs()
    modules = [discovery_bridge, identity, bootstrap]

    signature_mismatches = []

    for call in calls:
        method = call["method"]
        arg_count = call["arg_count"]
        
        func = None
        for mod in modules:
            if hasattr(mod, method):
                func = getattr(mod, method)
                break
        
        assert func is not None, f"Method {method} not found in modules"
        sig = inspect.signature(func)
        params = list(sig.parameters.values())

        # Check for varargs (*args)
        has_varargs = any(p.kind == inspect.Parameter.VAR_POSITIONAL for p in params)

        # Count min and max positional parameters
        positional_params = [
            p for p in params
            if p.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD)
        ]
        min_args = sum(1 for p in positional_params if p.default == inspect.Parameter.empty)
        max_args = len(positional_params) if not has_varargs else 999

        if not (min_args <= arg_count <= max_args):
            signature_mismatches.append(
                f"{method}(): Kotlin passes {arg_count} args, but Python expects between {min_args} and {max_args} args (sig: {sig})"
            )

    assert not signature_mismatches, "Chaquopy signature mismatches detected:\n" + "\n".join(signature_mismatches)


def test_chaquopy_exported_discovery_bridge_callable_smoketest():
    """
    Directly execute parameter-free and query functions to verify they don't crash or raise unexpected exceptions.
    """
    # Test diagnostics functions return valid JSON strings
    diag_json = discovery_bridge.get_tracker_diagnostics_json()
    assert isinstance(diag_json, str)

    upnp_json = discovery_bridge.get_upnp_details_json()
    assert isinstance(upnp_json, str)

    active_peers = discovery_bridge.get_active_peers_list()
    assert isinstance(active_peers, str)

    public_addrs = discovery_bridge.get_public_addresses_json()
    assert isinstance(public_addrs, str)

    # Test reset functions execute safely
    discovery_bridge.reset_stale_endpoint_cooldowns()
    try:
        discovery_bridge.set_ipv4_enabled(False)
    finally:
        discovery_bridge.set_ipv4_enabled(True)
