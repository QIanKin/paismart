# Vendored: Spider_XHS

本目录是 [cv-cat/Spider_XHS](https://github.com/cv-cat/Spider_XHS) 的 vendored copy，作为
小蜜蜂 Bee 平台小红书类 skill 的**共享 Python 库**。

## 为什么 vendored 而不是 submodule

1. 小红书签名 JS (`static/xhs_main_*.js`) 会被上游频繁替换；vendored 模式下由
   运维团队人工节奏升级，避免构建期自动抓上游失效版本
2. 客户现场部署可能不允许访问 GitHub
3. Skill 系统的 sandbox 根目录不方便做 submodule

## 怎么升级

```bash
# 在有网环境
git clone https://github.com/cv-cat/Spider_XHS /tmp/spider_xhs
# diff 看看 apis/ xhs_utils/ static/ 的变化，再覆盖：
rsync -a --delete --exclude .git --exclude author --exclude .env \
  /tmp/spider_xhs/ d:/Project/AI/PaiSmart/skills-bundled/_shared/Spider_XHS/
```

## 谁在用

skills-bundled 下所有以 `xhs-` 开头的 skill 都通过环境变量 `SPIDER_XHS_HOME` 指向本目录。

```python
import os, sys
sys.path.insert(0, os.environ["SPIDER_XHS_HOME"])
from apis.xhs_pc_apis import XHS_Apis
```

## 依赖

```
pip install -r requirements.txt
```

`PyExecJS` 需要 Node.js 20+（`npm install` 准备 JS 运行时签名算法）。

## License

MIT (上游 LICENSE 见 Spider_XHS 项目)
