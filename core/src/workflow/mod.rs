//! GitHub Actions 工作流 YAML 的**最小解析**（只服务「手动触发」表单）。
//!
//! 为什么需要它：GitHub REST 的 workflow 对象**不含** `inputs` 字段（实测字段只有
//! id/name/path/state/created_at/updated_at/url/html_url/badge_url），要做「填入参数执行工作流」，
//! 只能读 `path` 指向的 YAML 文件，自己解析 `on.workflow_dispatch.inputs`。
//!
//! 选 `yaml-rust2` 而不是 `serde_yaml` 的关键原因：后者基于 YAML 1.1，
//! 会把键 `on` 解析成布尔 `true`（GitHub Actions 文件的经典坑）；yaml-rust2 是 YAML 1.2，
//! `on` 保持字符串。这里仍对 `on` / `true` 两种键都做兼容，避免上游行为变化。
//!
//! 解析目标只三件事：
//! 1. 该工作流是否声明了 `workflow_dispatch`（没声明就不能手动触发，API 会 422）；
//! 2. 每个 input 的 `name / description / required / default / type / options`；
//! 3. 保持 YAML 中的声明顺序（表单按此顺序渲染）。
//!
//! 任何异常输入都不得 panic：解析失败一律返回「未启用 + 无输入」。

use serde_json::json;
use yaml_rust2::{Yaml, YamlLoader};

/// 一个 `workflow_dispatch` 输入项。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkflowInput {
    pub name: String,
    pub description: String,
    pub required: bool,
    pub default: String,
    /// string / boolean / choice / number / environment（缺省按 string）
    pub input_type: String,
    pub options: Vec<String>,
}

/// 解析结果。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DispatchSpec {
    pub enabled: bool,
    pub inputs: Vec<WorkflowInput>,
}

/// 解析工作流 YAML，得到「能否手动触发 + 输入定义」。
pub fn parse_dispatch_spec(yaml_text: &str) -> DispatchSpec {
    let docs = match YamlLoader::load_from_str(yaml_text) {
        Ok(d) => d,
        Err(_) => return DispatchSpec::default(),
    };
    let root = match docs.first() {
        Some(r) => r,
        None => return DispatchSpec::default(),
    };

    // `on` 在 YAML 1.2 是字符串；兼容被当成布尔 true 的解析器
    let on = match root.as_hash() {
        Some(h) => h
            .get(&Yaml::String("on".to_string()))
            .or_else(|| h.get(&Yaml::Boolean(true))),
        None => None,
    };
    let on = match on {
        Some(v) => v,
        None => return DispatchSpec::default(),
    };

    // 是否启用：None = 未声明；Some(None) = 声明了但无 inputs；Some(Some(node)) = 有 inputs 节点
    let dispatch: Option<Option<&Yaml>> = match on {
        // on: [push, workflow_dispatch]
        Yaml::Array(items) => items
            .iter()
            .any(|i| i.as_str() == Some("workflow_dispatch"))
            .then_some(None),
        // on: workflow_dispatch
        Yaml::String(s) if s == "workflow_dispatch" => Some(None),
        Yaml::Hash(h) => h.get(&Yaml::String("workflow_dispatch".to_string())).map(Some),
        _ => None,
    };

    let dispatch = match dispatch {
        Some(d) => d,
        None => return DispatchSpec::default(),
    };

    // on.workflow_dispatch 可以是 null（无输入）或含 inputs 的映射
    let inputs = dispatch
        .and_then(|node| node.as_hash())
        .and_then(|h| h.get(&Yaml::String("inputs".to_string())))
        .and_then(|v| v.as_hash());

    let inputs = match inputs {
        Some(h) => h
            .iter()
            .filter_map(|(k, v)| {
                let name = k.as_str()?.trim().to_string();
                if name.is_empty() {
                    return None;
                }
                let spec = v.as_hash();
                let description = spec
                    .and_then(|s| s.get(&Yaml::String("description".to_string())))
                    .map(scalar_to_string)
                    .unwrap_or_default();
                let required = spec
                    .and_then(|s| s.get(&Yaml::String("required".to_string())))
                    .and_then(|r| r.as_bool())
                    .unwrap_or(false);
                let default = spec
                    .and_then(|s| s.get(&Yaml::String("default".to_string())))
                    .map(scalar_to_string)
                    .unwrap_or_default();
                let input_type = spec
                    .and_then(|s| s.get(&Yaml::String("type".to_string())))
                    .and_then(|t| t.as_str())
                    .map(|t| t.trim().to_lowercase())
                    .filter(|t| !t.is_empty())
                    .unwrap_or_else(|| "string".to_string());
                let options = spec
                    .and_then(|s| s.get(&Yaml::String("options".to_string())))
                    .and_then(|o| o.as_vec())
                    .map(|arr| arr.iter().map(scalar_to_string).collect())
                    .unwrap_or_default();
                Some(WorkflowInput {
                    name,
                    description,
                    required,
                    default,
                    input_type,
                    options,
                })
            })
            .collect(),
        None => Vec::new(),
    };

    DispatchSpec {
        enabled: true,
        inputs,
    }
}

/// 转成 JNI 直接可返回的 JSON。
pub fn parse_dispatch_spec_json(yaml_text: &str) -> String {
    let spec = parse_dispatch_spec(yaml_text);
    let inputs: Vec<serde_json::Value> = spec
        .inputs
        .iter()
        .map(|i| {
            json!({
                "name": i.name,
                "description": i.description,
                "required": i.required,
                "default": i.default,
                "type": i.input_type,
                "options": i.options,
            })
        })
        .collect();
    json!({ "enabled": spec.enabled, "inputs": inputs }).to_string()
}

/// YAML 标量 → 字符串（bool/数字按字面量输出，供表单预填）。
fn scalar_to_string(v: &Yaml) -> String {
    match v {
        Yaml::String(s) => s.clone(),
        Yaml::Boolean(b) => b.to_string(),
        Yaml::Integer(i) => i.to_string(),
        Yaml::Real(r) => r.clone(),
        Yaml::Null => String::new(),
        _ => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_mapping_inputs() {
        let yaml = r#"
name: CI
on:
  push:
    branches: [main]
  workflow_dispatch:
    inputs:
      version:
        description: 发布版本号
        required: true
        type: string
      dry_run:
        description: 只做检查
        type: boolean
        default: false
      target:
        description: 目标环境
        type: choice
        options:
          - staging
          - production
jobs:
  build:
    runs-on: ubuntu-latest
"#;
        let spec = parse_dispatch_spec(yaml);
        assert!(spec.enabled);
        assert_eq!(spec.inputs.len(), 3);
        assert_eq!(spec.inputs[0].name, "version");
        assert_eq!(spec.inputs[0].input_type, "string");
        assert!(spec.inputs[0].required);
        assert_eq!(spec.inputs[1].name, "dry_run");
        assert_eq!(spec.inputs[1].input_type, "boolean");
        assert_eq!(spec.inputs[1].default, "false");
        assert_eq!(spec.inputs[2].options, vec!["staging", "production"]);
    }

    #[test]
    fn disabled_without_workflow_dispatch() {
        let spec = parse_dispatch_spec("on:\n  push:\n    branches: [main]\n");
        assert!(!spec.enabled);
        assert!(spec.inputs.is_empty());
    }

    #[test]
    fn recognizes_array_and_scalar_shorthand() {
        assert!(parse_dispatch_spec("on: [push, workflow_dispatch]\n").enabled);
        assert!(parse_dispatch_spec("on: workflow_dispatch\n").enabled);
        assert!(parse_dispatch_spec("on:\n  workflow_dispatch:\n").enabled);
    }

    #[test]
    fn defaults_type_to_string() {
        let spec = parse_dispatch_spec(
            "on:\n  workflow_dispatch:\n    inputs:\n      name:\n        description: x\n",
        );
        assert_eq!(spec.inputs[0].input_type, "string");
        assert!(!spec.inputs[0].required);
    }

    #[test]
    fn invalid_input_does_not_panic() {
        assert!(!parse_dispatch_spec("").enabled);
        assert!(!parse_dispatch_spec(":\n:\n:").enabled);
        assert!(!parse_dispatch_spec("on: 123").enabled);
        // inputs 不是映射
        assert!(parse_dispatch_spec("on:\n  workflow_dispatch:\n    inputs: 5\n").enabled);
    }

    #[test]
    fn json_output_shape_is_stable() {
        let out = parse_dispatch_spec_json("on:\n  workflow_dispatch:\n");
        assert_eq!(out, r#"{"enabled":true,"inputs":[]}"#);
    }
}
