from __future__ import annotations

from pathlib import Path
from typing import Callable

import json
import re
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.common.i18n.exceptions import (  # noqa: E402
    DuplicateTranslationKeyError,
    InvalidTranslationFileError,
    LocaleNotFoundError,
)
from src.common.i18n.loaders import (  # noqa: E402
    DEFAULT_LOCALE,
    PLURAL_CATEGORIES,
    TranslationValue,
    discover_locales,
    get_locales_root,
    load_locale_catalog,
    validate_translation_value,
)
from src.common.i18n.loaders import extract_placeholders  # noqa: E402
from src.common.prompt_i18n import (  # noqa: E402
    discover_prompt_locales,
    extract_prompt_placeholders,
    get_prompts_root,
    iter_prompt_files,
)

HAN_CHARACTER_PATTERN = re.compile(r"[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]")
I18NEXT_PLACEHOLDER_PATTERN = re.compile(r"\{\{\s*([^\s,}]+)(?:\s*,[^}]*)?\s*\}\}")
DASHBOARD_DEFAULT_LOCALE = "zh"


def contains_han_characters(text: str) -> bool:
    return HAN_CHARACTER_PATTERN.search(text) is not None


def extract_i18next_placeholders(template: str) -> set[str]:
    placeholders: set[str] = set()
    for match in I18NEXT_PLACEHOLDER_PATTERN.finditer(template):
        placeholder_name = match.group(1)
        placeholders.add(placeholder_name.split(".", maxsplit=1)[0].split("[", maxsplit=1)[0])
    return placeholders


def iter_translation_strings(value: TranslationValue) -> list[str]:
    if isinstance(value, str):
        return [value]
    return [value[category] for category in sorted(value.keys())]


def iter_shared_translation_strings(
    source_value: TranslationValue, target_value: TranslationValue
) -> list[tuple[str, str]]:
    if isinstance(source_value, str) or isinstance(target_value, str):
        if isinstance(source_value, str) and isinstance(target_value, str):
            return [(source_value, target_value)]
        return []

    shared_categories = sorted(set(source_value.keys()) & set(target_value.keys()))
    return [(source_value[category], target_value[category]) for category in shared_categories]


def locale_requires_latin_only_validation(locale: str) -> bool:
    normalized_locale = locale.lower()
    return normalized_locale == "en" or normalized_locale.startswith("en-")


def validate_locale_content(
    key: str,
    source_value: TranslationValue,
    target_value: TranslationValue,
    locale: str,
    errors: list[str],
    locale_label: str | None = None,
) -> None:
    resolved_locale_label = locale_label or locale
    target_texts = iter_translation_strings(target_value)

    if any(
        source_text == target_text and contains_han_characters(source_text)
        for source_text, target_text in iter_shared_translation_strings(source_value, target_value)
    ):
        errors.append(
            f"[{resolved_locale_label}] key '{key}' 直接保留了包含中文字符的 source 文案（仓库级校验策略），请提供目标语言翻译"
        )

    if locale_requires_latin_only_validation(locale) and any(contains_han_characters(text) for text in target_texts):
        errors.append(f"[{resolved_locale_label}] key '{key}' 仍包含中文字符，请移除源语言残留后再提交")


def validate_translation_pair(
    key: str,
    source_value: TranslationValue,
    target_value: TranslationValue,
    locale: str,
    errors: list[str],
    placeholder_extractor: Callable[[str], set[str]] = extract_placeholders,
    locale_label: str | None = None,
) -> None:
    resolved_locale_label = locale_label or locale
    if isinstance(source_value, str):
        if not isinstance(target_value, str):
            errors.append(
                f"[{resolved_locale_label}] key '{key}' 与 source 的类型不一致：source=string, target=plural"
            )
            return
        if placeholder_extractor(source_value) != placeholder_extractor(target_value):
            errors.append(f"[{resolved_locale_label}] key '{key}' 的占位符集合与 source 不一致")
        return

    if not isinstance(target_value, dict):
        errors.append(f"[{resolved_locale_label}] key '{key}' 与 source 的类型不一致：source=plural, target=string")
        return

    source_categories = set(source_value.keys())
    target_categories = set(target_value.keys())
    if source_categories != target_categories:
        errors.append(
            f"[{resolved_locale_label}] key '{key}' 的 plural category 不一致："
            f"source={sorted(source_categories)}, target={sorted(target_categories)}"
        )

    for category in sorted(source_categories & target_categories):
        source_placeholders = placeholder_extractor(source_value[category])
        target_placeholders = placeholder_extractor(target_value[category])
        if source_placeholders != target_placeholders:
            errors.append(
                f"[{resolved_locale_label}] key '{key}' 的 plural category '{category}' 占位符集合与 source 不一致"
            )


def get_dashboard_locales_root(locales_root: Path | None = None) -> Path:
    if locales_root is not None:
        return locales_root.resolve()
    return (PROJECT_ROOT / "dashboard" / "src" / "i18n" / "locales").resolve()


def discover_dashboard_locales(locales_root: Path | None = None) -> list[str]:
    root = get_dashboard_locales_root(locales_root)
    if not root.exists():
        return []

    locale_names = [path.stem for path in root.glob("*.json") if path.is_file()]
    return sorted(locale_names)


def is_plural_translation_node(value: object) -> bool:
    if not isinstance(value, dict) or not value:
        return False

    return all(
        isinstance(category, str) and category in PLURAL_CATEGORIES and isinstance(category_value, str)
        for category, category_value in value.items()
    )


def flatten_dashboard_translation_mapping(
    value: dict[str, object],
    file_path: Path,
    translations: dict[str, TranslationValue],
    parent_keys: list[str] | None = None,
) -> None:
    current_parent_keys = parent_keys or []
    if not value:
        if current_parent_keys:
            raise InvalidTranslationFileError(
                f"{file_path} 中的 key '{'.'.join(current_parent_keys)}' 不能为空对象"
            )
        raise InvalidTranslationFileError(f"{file_path} 顶层不能为空对象")

    for raw_key, raw_value in value.items():
        if not isinstance(raw_key, str):
            raise InvalidTranslationFileError(f"{file_path} 中存在非字符串 key")

        normalized_key = raw_key.strip()
        if not normalized_key:
            raise InvalidTranslationFileError(f"{file_path} 中存在空字符串 key")

        current_key_parts = [*current_parent_keys, normalized_key]
        current_key = ".".join(current_key_parts)

        if isinstance(raw_value, str):
            if current_key in translations:
                raise DuplicateTranslationKeyError(f"{file_path} 中存在重复 key: '{current_key}'")
            translations[current_key] = raw_value
            continue

        if is_plural_translation_node(raw_value):
            if current_key in translations:
                raise DuplicateTranslationKeyError(f"{file_path} 中存在重复 key: '{current_key}'")
            translations[current_key] = validate_translation_value(current_key, raw_value, file_path)
            continue

        if isinstance(raw_value, dict):
            flatten_dashboard_translation_mapping(raw_value, file_path, translations, current_key_parts)
            continue

        raise InvalidTranslationFileError(f"{file_path} 中的 key '{current_key}' 必须是字符串或对象")


def load_dashboard_translation_file(file_path: Path) -> dict[str, TranslationValue]:
    try:
        raw_payload = json.loads(file_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise InvalidTranslationFileError(f"{file_path} 不是合法 JSON: {exc}") from exc

    if not isinstance(raw_payload, dict):
        raise InvalidTranslationFileError(f"{file_path} 顶层必须是 JSON object")

    translations: dict[str, TranslationValue] = {}
    flatten_dashboard_translation_mapping(raw_payload, file_path, translations)
    return translations


def load_dashboard_locale_catalog(
    locale: str,
    locales_root: Path | None = None,
) -> dict[str, TranslationValue]:
    locale_file = get_dashboard_locales_root(locales_root) / f"{locale}.json"
    if not locale_file.exists():
        raise LocaleNotFoundError(f"未找到 locale 文件: {locale_file}")

    return load_dashboard_translation_file(locale_file)


def validate_dashboard_json_locales(locales_root: Path | None = None) -> list[str]:
    resolved_locales_root = get_dashboard_locales_root(locales_root)
    locales = discover_dashboard_locales(resolved_locales_root)
    errors: list[str] = []

    if DASHBOARD_DEFAULT_LOCALE not in locales:
        errors.append(f"[dashboard] 缺少默认 locale 文件: {DASHBOARD_DEFAULT_LOCALE}.json")
        return errors

    catalogs: dict[str, dict[str, TranslationValue]] = {}
    for locale in locales:
        try:
            catalogs[locale] = load_dashboard_locale_catalog(locale, resolved_locales_root)
        except Exception as exc:
            errors.append(f"[dashboard:{locale}] 加载失败: {exc}")

    source_catalog = catalogs.get(DASHBOARD_DEFAULT_LOCALE)
    if source_catalog is None:
        return errors

    source_keys = set(source_catalog.keys())
    for locale, catalog in catalogs.items():
        if locale == DASHBOARD_DEFAULT_LOCALE:
            continue

        locale_label = f"dashboard:{locale}"
        locale_keys = set(catalog.keys())
        for key in sorted(source_keys - locale_keys):
            errors.append(f"[{locale_label}] 缺少 key: {key}")
        for key in sorted(locale_keys - source_keys):
            errors.append(f"[{locale_label}] 存在多余 key: {key}")

        for key in sorted(source_keys & locale_keys):
            source_value = source_catalog[key]
            target_value = catalog[key]
            validate_translation_pair(
                key,
                source_value,
                target_value,
                locale,
                errors,
                placeholder_extractor=extract_i18next_placeholders,
                locale_label=locale_label,
            )
            if isinstance(source_value, str) == isinstance(target_value, str):
                validate_locale_content(key, source_value, target_value, locale, errors, locale_label=locale_label)

    return errors


def validate_json_locales(locales_root: Path | None = None) -> list[str]:
    resolved_locales_root = get_locales_root(locales_root)
    locales = discover_locales(resolved_locales_root)
    errors: list[str] = []

    if DEFAULT_LOCALE not in locales:
        errors.append(f"缺少默认 locale 目录: {DEFAULT_LOCALE}")
        return errors

    catalogs: dict[str, dict[str, TranslationValue]] = {}
    for locale in locales:
        try:
            catalogs[locale] = load_locale_catalog(locale, resolved_locales_root)
        except Exception as exc:
            errors.append(f"[{locale}] 加载失败: {exc}")

    source_catalog = catalogs.get(DEFAULT_LOCALE)
    if source_catalog is None:
        return errors

    source_keys = set(source_catalog.keys())
    for locale, catalog in catalogs.items():
        if locale == DEFAULT_LOCALE:
            continue

        locale_keys = set(catalog.keys())
        for key in sorted(source_keys - locale_keys):
            errors.append(f"[{locale}] 缺少 key: {key}")
        for key in sorted(locale_keys - source_keys):
            errors.append(f"[{locale}] 存在多余 key: {key}")

        for key in sorted(source_keys & locale_keys):
            source_value = source_catalog[key]
            target_value = catalog[key]
            validate_translation_pair(key, source_value, target_value, locale, errors)
            if isinstance(source_value, str) == isinstance(target_value, str):
                validate_locale_content(key, source_value, target_value, locale, errors)

    return errors


def build_prompt_catalog(locale_dir: Path) -> dict[Path, Path]:
    return {path.relative_to(locale_dir): path for path in iter_prompt_files(locale_dir)}


def validate_prompt_templates(prompts_root: Path | None = None) -> tuple[list[str], list[str]]:
    resolved_prompts_root = get_prompts_root(prompts_root)
    prompt_locales = set(discover_prompt_locales(resolved_prompts_root))
    known_locales = [locale for locale in discover_locales(get_locales_root()) if locale != DEFAULT_LOCALE]
    errors: list[str] = []
    warnings: list[str] = []

    if DEFAULT_LOCALE not in prompt_locales:
        errors.append(f"缺少默认 Prompt locale 目录: {DEFAULT_LOCALE}")
        return errors, warnings

    source_dir = resolved_prompts_root / DEFAULT_LOCALE
    source_files = build_prompt_catalog(source_dir)
    source_relative_paths = set(source_files.keys())

    for locale in known_locales:
        locale_dir = resolved_prompts_root / locale
        if not locale_dir.exists():
            warnings.append(f"[prompt:{locale}] 缺少 locale 目录，运行时将回退到 {DEFAULT_LOCALE}")
            continue

        locale_files = build_prompt_catalog(locale_dir)
        locale_relative_paths = set(locale_files.keys())

        for relative_path in sorted(source_relative_paths - locale_relative_paths):
            warnings.append(f"[prompt:{locale}] 缺少模板: {relative_path.as_posix()}，运行时将回退到 {DEFAULT_LOCALE}")

        for relative_path in sorted(locale_relative_paths - source_relative_paths):
            warnings.append(f"[prompt:{locale}] 存在额外模板: {relative_path.as_posix()}")

        for relative_path in sorted(source_relative_paths & locale_relative_paths):
            source_text = source_files[relative_path].read_text(encoding="utf-8")
            locale_text = locale_files[relative_path].read_text(encoding="utf-8")

            source_placeholders = extract_prompt_placeholders(source_text)
            locale_placeholders = extract_prompt_placeholders(locale_text)
            if source_placeholders != locale_placeholders:
                errors.append(
                    "[prompt:{locale}] 模板 '{path}' 的占位符集合与 source 不一致："
                    "source={source_placeholders}, target={target_placeholders}".format(
                        locale=locale,
                        path=relative_path.as_posix(),
                        source_placeholders=sorted(source_placeholders),
                        target_placeholders=sorted(locale_placeholders),
                    )
                )

            if source_text == locale_text:
                warnings.append(f"[prompt:{locale}] 模板 '{relative_path.as_posix()}' 与 source 完全相同，可能尚未翻译")

    return errors, warnings


def _print_warnings(warnings: list[str]) -> None:
    if not warnings:
        return
    print(f"warnings ({len(warnings)}):")
    for warning in warnings[:10]:
        print(f"  - {warning}")
    if len(warnings) > 10:
        print(f"  - ... 另外还有 {len(warnings) - 10} 条 warning")


def main() -> int:
    errors = validate_json_locales()
    errors.extend(validate_dashboard_json_locales())
    prompt_errors, prompt_warnings = validate_prompt_templates()
    errors.extend(prompt_errors)

    if errors:
        print("i18n validation failed:")
        for error in errors:
            print(f"  - {error}")
        _print_warnings(prompt_warnings)
        return 1

    print("i18n validation passed.")
    _print_warnings(prompt_warnings)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
