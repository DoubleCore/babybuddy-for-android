#!/usr/bin/env python3
"""
Portable zero-dependency generator for app/src/main/res/values*/help_strings.xml
from resources/help_images/help*.md (mirrors resources/help_images/process_markdown.py
for environments without pandoc/make, such as Windows).
"""

import os
import re
from dataclasses import dataclass, field
from typing import List, Optional
from xml.etree import ElementTree as ET


@dataclass
class Section:
    title: str
    image: Optional[str] = None
    paragraphs: List[str] = field(default_factory=list)


@dataclass
class Topic:
    title: str
    sections: List[Section] = field(default_factory=list)


def escape_quotes(text: str) -> str:
    # Escape straight single and double quotes for Android AAPT2 XML string resources
    escaped = re.sub(r'(?<!\\)"', r'\\"', text)
    escaped = re.sub(r"(?<!\\)'", r"\\'", escaped)
    return escaped


def parse_help_markdown(md_text: str) -> List[Topic]:
    topics: List[Topic] = []
    blocks = re.split(r"\r?\n\s*\r?\n", md_text.strip())

    for raw_block in blocks:
        block = raw_block.strip()
        if not block:
            continue
        if block.startswith("# "):
            title = escape_quotes(block[2:].strip())
            topics.append(Topic(title=title))
        elif block.startswith("## "):
            if not topics:
                raise ValueError(f"Section header before topic: {block}")
            title = escape_quotes(block[3:].strip())
            topics[-1].sections.append(Section(title=title))
        elif block.startswith("!["):
            if not topics or not topics[-1].sections:
                raise ValueError(f"Image outside section: {block}")
            m = re.match(r"^!\[[^\]]*\]\(([^)]+)\)", block)
            if not m:
                continue
            img_path = m.group(1).split("/")[-1]
            if "." in img_path:
                img_path = "".join(img_path.split(".")[:-1])
            topics[-1].sections[-1].image = img_path
        else:
            if not topics or not topics[-1].sections:
                continue
            normalized = " ".join(line.strip() for line in block.splitlines() if line.strip())
            topics[-1].sections[-1].paragraphs.append(escape_quotes(normalized))

    return topics


def render_help_xml(topics: List[Topic]) -> str:
    def string_el(name: str, content: str) -> ET.Element:
        el = ET.Element("string")
        el.attrib["name"] = name
        el.text = content
        return el

    help_xml = ET.fromstring("<resources />")
    for topic_i, topic in enumerate(topics):
        help_xml.append(ET.Comment(topic.title))
        for section_i, section in enumerate(topic.sections):
            item_id = f"help_item_{topic_i + 1}_{section_i + 1}"
            help_xml.append(string_el(f"{item_id}_title", section.title))
            help_xml.append(string_el(f"{item_id}_image", section.image or "none"))
            help_xml.append(string_el(f"{item_id}_text", "\n\n".join(section.paragraphs).strip()))

    indent = "\n    "
    has_element = False
    for el in help_xml:
        has_element = True
        el.tail = indent

    if has_element:
        help_xml.text = indent
        help_xml[-1].tail = "\n"

    return '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(help_xml, encoding="unicode")


def main() -> None:
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    postfixes = ["", "-nl", "-de-rDE", "-zh", "-zh-rCN"]
    for postfix in postfixes:
        md_path = os.path.join(repo_root, "resources", "help_images", f"help{postfix}.md")
        xml_path = os.path.join(repo_root, "app", "src", "main", "res", f"values{postfix}", "help_strings.xml")
        if not os.path.exists(md_path):
            continue
        with open(md_path, "r", encoding="utf-8") as f:
            topics = parse_help_markdown(f.read())
        os.makedirs(os.path.dirname(xml_path), exist_ok=True)
        with open(xml_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(render_help_xml(topics))


if __name__ == "__main__":
    main()
