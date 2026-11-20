# 维护项目镜像命名与生命周期

## 固定规范

后续 NAS 构建统一经过 `plugin-maintenance/scripts/maintenance_image.py`，不再直接拼接历史 `run-...` 标签。

命名为 `at-plugin-maintenance-<用途>:v<项目版本>-<UTC构建批次>-<源码或上下文摘要前12位>`。
例如：`at-plugin-maintenance-source:v0.59.0-snapshot-20261115t013000z-283f48977772`。
用途应区分 `fixture`、`source`、`http`、`embedded`、`workbench`、`source-dependencies` 等，不混称完整独立维护平台容器。
相同内容优先复用已有镜像，不为重新测试重复构建。工作区有未提交改动时使用实际源码或构建上下文摘要，不能只填写 HEAD。

新入口强制生成 project、role、version、revision、build、retention 标签及 OCI 创建时间/版本/源码摘要。
临时测试使用 `temporary`，正式交付使用 `release`；这些标签不是业务验收成功证明。密钥、Cookie 和账户不进入镜像标签或构建参数。

## 构建入口

工具只负责镜像管理，不修改爬虫授权、隔离策略、生产配置或发布行为。NAS 本地执行构建入口，回执取回 D 盘；本机无 Docker 时只运行 `plan`。

```powershell
py -3.14 -B -X utf8 plugin-maintenance/scripts/maintenance_image.py plan --role source --version 0.59.0-SNAPSHOT --revision 283f4897777275b124e3b0a9495c7e36925615ff993152710c3d66831fc5d75d
```

实际 `build` 还需提供 `--dockerfile`、`--context` 和新的 `--receipt`。原有离线构建参数以 `--build-arg` 传入；需要的网络模式显式使用 `--network`，不强加额外站点访问限制。
工具自动加规范标签并记录真实完整镜像 ID，构建完成状态为 `BUILT_NOT_ACCEPTED`，不能据此认为业务验收通过。

## 迭代结束必须清理

每次验收结束或失败处理结束都执行一次同用途镜像清理，不另设周期任务，也不每次跑全量测试。
保留当前正式采用版本及同用途最近一个已验收回滚版本。失败试验镜像若没有引用、没有排查保留理由，在该次迭代结束时清理，不无限累积。

交付调用方从实际配置/运行状态生成最新保留清单，三个字段均为完整镜像 ID 数组：

```json
{
  "current": ["sha256:实际当前镜像完整ID"],
  "rollback": ["sha256:上一个已验收镜像完整ID"],
  "runtime": ["sha256:仍被容器外执行器使用的镜像完整ID"]
}
```

上例是结构说明，中文占位值不能执行。首次部署没有回滚版本时 `rollback` 使用空数组。
调用方必须核对 NAS broker 的 owner/存活进程或打开的套接字，将仍在使用的镜像放入 `runtime`；不能仅凭容器列表判断执行器镜像已弃用。

```text
python3 -B maintenance_image.py retire --role source --keep-file keep-images.json --receipt retire-本次唯一批次.json
python3 -B maintenance_image.py retire --role source --keep-file keep-images.json --receipt retire-另一个唯一批次.json --apply
```

不带 `--apply` 时只生成清理计划。实际迭代脚本在验收/失败处理结束后调用带 `--apply` 的入口。
清理器额外自动保留所有运行和停止容器的镜像引用，只删除 project 和 role 标签精确匹配且未保留的镜像。
每次使用新回执文件；失败的 STARTED 回执先核对真实状态，不盲目重放。
旧镜像标签、ID、删除或跳过结果进入回执，发生冲突则保留并报告。默认不删除容器。禁止强制删、顺带删父镜像或全局 prune。

## 显式退役停止实例

停止容器仍会引用旧镜像。需要同时退役时显式增加 `--retire-stopped-containers`，仍先查看计划：

```text
python3 -B maintenance_image.py retire --role embedded --keep-file keep-images.json --receipt retire-plan.json --retire-stopped-containers
python3 -B maintenance_image.py retire --role embedded --keep-file keep-images.json --receipt retire-apply.json --retire-stopped-containers --apply
```

仅处理项目和用途标签精确匹配、处于停止状态、`/data` 绑定宿主持久目录且不在 current/rollback/runtime 保留清单中的实例。删除前再次读取状态与身份，运行中、挂载不明确或数据不持久的实例保留并报告。只删除容器引用，不删除数据目录或卷；解除引用后再按同一范围退役镜像。
