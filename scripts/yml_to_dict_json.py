#!/usr/bin/env python3
"""Turns a PaddleOCR inference.yml into dict.json (a JSON array: one entry per recogniser class).

Usage: yml_to_dict_json.py inference.yml dict.json

Why not just yaml.safe_load? In these files some entries are plain scalars such as ~ which a YAML
parser would turn into null, shifting every later character by one. Reading the list literally
keeps the order and the characters exact.
"""
import json
import sys


def unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == "'" and value[-1] == "'":
        return value[1:-1].replace("''", "'")
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        try:
            return json.loads(value)
        except ValueError:
            return value[1:-1].encode("utf-8").decode("unicode_escape")
    return value  # plain scalar: take it literally (do NOT strip: U+3000 is an entry)


def main(src: str, dst: str) -> None:
    with open(src, "rb") as f:
        lines = f.read().decode("utf-8").split("\n")  # split on \n only, not on U+2028 etc.

    chars = []
    in_dict = False
    for line in lines:
        if not in_dict:
            if line.strip() == "character_dict:":
                in_dict = True
            continue
        if line.startswith("  - "):
            chars.append(unquote(line[4:].rstrip("\r")))
        else:
            break  # first line after the list

    if len(chars) < 100:
        sys.exit(f"error: only {len(chars)} dictionary entries found in {src}; is it a recogniser inference.yml?")

    with open(dst, "w", encoding="utf-8") as f:
        json.dump(chars, f, ensure_ascii=False)
    print(f"dict.json: {len(chars)} characters (the model has {len(chars) + 2} classes: blank + these + space)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
