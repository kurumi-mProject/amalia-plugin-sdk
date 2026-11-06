#!/usr/bin/env python3
"""Create a self-contained Markdown documentation pack for an Amalia UI authoring model."""
import argparse
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--template", choices=("collection", "controls", "preferences", "form", "detail-result"),
                        default="preferences", help="Include one task-appropriate complete template")
    parser.add_argument("--output", required=True, type=Path, help="New Markdown output file")
    args = parser.parse_args()
    sources = [
        ROOT / "docs/AI_PLUGIN_AUTHORING.md",
        ROOT / "docs/AI_UI_UX_GUIDE_RU.md",
        ROOT / "docs/UI_TEMPLATES_RU.md",
        ROOT / "docs/UI_COMPONENTS.json",
        ROOT / "docs/examples/templates" / (args.template + ".json"),
    ]
    try:
        parts = ["# Amalia UI authoring pack\n\n"
                 "Add the user's actual task and exact provider descriptors, schemas and grants. "
                 "This pack does not supply provider implementations or credentials.\n"]
        for source in sources:
            text = source.read_text(encoding="utf-8")
            if source.suffix == ".json":
                import json
                json.loads(text)
                text = "```json\n" + text.rstrip() + "\n```\n"
            parts.append("\n---\n\n# Source: " + source.relative_to(ROOT).as_posix() + "\n\n" + text)
        sdk = (ROOT / "docs/SDK_GUIDE_RU.md").read_text(encoding="utf-8")
        start = sdk.index("## 14.")
        end = sdk.index("\n## 15.", start)
        parts.append("\n---\n\n# Source: docs/SDK_GUIDE_RU.md, section 14\n\n" + sdk[start:end])
        payload = "".join(parts)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as output:
            output.write(payload)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Cannot create documentation pack: {error}\n")
    print(f"Created {args.output} ({len(payload.encode('utf-8'))} bytes)")


if __name__ == "__main__":
    main()
