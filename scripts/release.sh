#!/usr/bin/env bash
set -euo pipefail

# BYD Auto Dark Wallpaper 交互式版本发布脚本 (Bash / Git Bash / Linux / macOS)
# 用法: ./scripts/release.sh [--dry-run]

DRY_RUN=false
for arg in "$@"; do
    case "$arg" in
        --dry-run)
            DRY_RUN=true
            ;;
        -h|--help)
            echo "用法: $0 [--dry-run]"
            echo "  --dry-run  仅演练并展示将要执行的更改与命令，不修改文件、不提交也不推送"
            exit 0
            ;;
        *)
            echo "错误: 未知参数 '$arg'" >&2
            echo "用法: $0 [--dry-run]" >&2
            exit 1
            ;;
    esac
done

# 定位仓库根目录
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
    echo "错误: 当前目录不在 Git 仓库中。" >&2
    exit 1
fi
cd "$REPO_ROOT"

GRADLE_FILE="$REPO_ROOT/app/build.gradle"
if [ ! -f "$GRADLE_FILE" ]; then
    echo "错误: 未找到 $GRADLE_FILE" >&2
    exit 1
fi

# 检查当前分支（不允许 detached HEAD）
CURRENT_BRANCH="$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
if [ -z "$CURRENT_BRANCH" ]; then
    echo "错误: 当前处于游离 HEAD 状态，请先切换到发布分支（例如 master）。" >&2
    exit 1
fi

# 检查工作区是否干净
if [ -n "$(git status --porcelain)" ]; then
    echo "错误: 当前 Git 工作区存在未提交的修改，请先提交或暂存后再运行发布脚本。" >&2
    git status --short >&2
    exit 1
fi

# 从 app/build.gradle 读取当前 versionName 与 versionCode
CURRENT_VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+["'\'']([^"'\'']+)["'\''].*/\1/p' "$GRADLE_FILE" | head -n 1)"
CURRENT_CODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$GRADLE_FILE" | head -n 1)"

if [ -z "$CURRENT_VERSION" ] || [ -z "$CURRENT_CODE" ]; then
    echo "错误: 无法从 app/build.gradle 解析 versionName 或 versionCode。" >&2
    exit 1
fi

# 解析语义化版本号: MAJOR.MINOR.PATCH[-PRERELEASE]
SEMVER_REGEX='^([0-9]+)\.([0-9]+)\.([0-9]+)(-([0-9A-Za-z.-]+))?$'
if [[ ! "$CURRENT_VERSION" =~ $SEMVER_REGEX ]]; then
    echo "错误: 当前版本号 '$CURRENT_VERSION' 不符合 SemVer 格式 (X.Y.Z 或 X.Y.Z-prerelease)。" >&2
    exit 1
fi

MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"
PRERELEASE="${BASH_REMATCH[5]:-}"

# 计算候选版本号
CANDIDATE_KEEP="$CURRENT_VERSION"

if [ -n "$PRERELEASE" ]; then
    PATCH_LABEL="release (转正式版)"
    CANDIDATE_PATCH="${MAJOR}.${MINOR}.${PATCH}"
else
    PATCH_LABEL="patch             "
    CANDIDATE_PATCH="${MAJOR}.${MINOR}.$((PATCH + 1))"
fi

CANDIDATE_MINOR="${MAJOR}.$((MINOR + 1)).0"
CANDIDATE_MAJOR="$((MAJOR + 1)).0.0"

# 计算 alpha 候选版本
if [ -z "$PRERELEASE" ]; then
    CANDIDATE_ALPHA="${MAJOR}.${MINOR}.$((PATCH + 1))-alpha.1"
elif [[ "$PRERELEASE" =~ ^alpha\.([0-9]+)$ ]]; then
    CANDIDATE_ALPHA="${MAJOR}.${MINOR}.${PATCH}-alpha.$((BASH_REMATCH[1] + 1))"
elif [ "$PRERELEASE" = "alpha" ]; then
    CANDIDATE_ALPHA="${MAJOR}.${MINOR}.${PATCH}-alpha.1"
else
    CANDIDATE_ALPHA="${MAJOR}.${MINOR}.$((PATCH + 1))-alpha.1"
fi

# 计算 beta 候选版本
if [ -z "$PRERELEASE" ]; then
    CANDIDATE_BETA="${MAJOR}.${MINOR}.$((PATCH + 1))-beta.1"
elif [[ "$PRERELEASE" =~ ^alpha(\.[0-9]+)?$ ]]; then
    CANDIDATE_BETA="${MAJOR}.${MINOR}.${PATCH}-beta.1"
elif [[ "$PRERELEASE" =~ ^beta\.([0-9]+)$ ]]; then
    CANDIDATE_BETA="${MAJOR}.${MINOR}.${PATCH}-beta.$((BASH_REMATCH[1] + 1))"
elif [ "$PRERELEASE" = "beta" ]; then
    CANDIDATE_BETA="${MAJOR}.${MINOR}.${PATCH}-beta.1"
else
    CANDIDATE_BETA="${MAJOR}.${MINOR}.$((PATCH + 1))-beta.1"
fi

echo "=========================================="
echo "  BYD Auto Dark Wallpaper 版本发布助手"
echo "=========================================="
if [ "$DRY_RUN" = true ]; then
    echo "  [演练模式 --dry-run：不会修改文件或推送]"
fi
echo "当前分支:    $CURRENT_BRANCH"
echo "当前版本:    $CURRENT_VERSION (versionCode: $CURRENT_CODE)"
echo ""
echo "请选择目标版本号:"
echo "  [1] 不变               -> $CANDIDATE_KEEP"
echo "  [2] $PATCH_LABEL -> $CANDIDATE_PATCH"
echo "  [3] minor              -> $CANDIDATE_MINOR"
echo "  [4] major              -> $CANDIDATE_MAJOR"
echo "  [5] alpha              -> $CANDIDATE_ALPHA"
echo "  [6] beta               -> $CANDIDATE_BETA"
echo "  [7] 自行输入"
echo "  [0] 取消"
echo ""

TARGET_VERSION=""
while [ -z "$TARGET_VERSION" ]; do
    read -r -p "请输入选项编号 [0-7]: " CHOICE
    case "$CHOICE" in
        1)
            TARGET_VERSION="$CANDIDATE_KEEP"
            ;;
        2)
            TARGET_VERSION="$CANDIDATE_PATCH"
            ;;
        3)
            TARGET_VERSION="$CANDIDATE_MINOR"
            ;;
        4)
            TARGET_VERSION="$CANDIDATE_MAJOR"
            ;;
        5)
            TARGET_VERSION="$CANDIDATE_ALPHA"
            ;;
        6)
            TARGET_VERSION="$CANDIDATE_BETA"
            ;;
        7)
            while true; do
                read -r -p "请输入自定义版本号 (例如 2.2.0 或 2.2.0-rc.1，输入 q 返回): " CUSTOM_INPUT
                if [ "$CUSTOM_INPUT" = "q" ] || [ "$CUSTOM_INPUT" = "Q" ]; then
                    break
                fi
                # 去除前后空白与可选的 v/V 前缀
                CUSTOM_CLEAN="$(echo "$CUSTOM_INPUT" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/^[vV]//')"
                if [[ "$CUSTOM_CLEAN" =~ $SEMVER_REGEX ]]; then
                    TARGET_VERSION="$CUSTOM_CLEAN"
                    break
                else
                    echo "格式无效：请输入类似 2.2.0 或 2.2.0-alpha.1 的 SemVer 版本号。"
                fi
            done
            ;;
        0)
            echo "已取消发布。"
            exit 0
            ;;
        *)
            echo "无效选项 '$CHOICE'，请输入 0 到 7 之间的数字。"
            ;;
    esac
done

TAG_NAME="v${TARGET_VERSION}"
VERSION_CHANGED=true
if [ "$TARGET_VERSION" = "$CURRENT_VERSION" ]; then
    VERSION_CHANGED=false
    TARGET_CODE="$CURRENT_CODE"
else
    TARGET_CODE="$((CURRENT_CODE + 1))"
fi

# 检查本地与远程 Tag 是否已存在
if git rev-parse -q --verify "refs/tags/${TAG_NAME}" >/dev/null 2>&1; then
    echo "错误: 本地已存在标签 '${TAG_NAME}'，请先确认版本号或清理旧标签。" >&2
    exit 1
fi

REMOTE_NAME="origin"
if ! git remote get-url "$REMOTE_NAME" >/dev/null 2>&1; then
    echo "错误: 未配置名为 '${REMOTE_NAME}' 的远程仓库。" >&2
    exit 1
fi

REMOTE_TAG_CHECK="$(git ls-remote --tags "$REMOTE_NAME" "refs/tags/${TAG_NAME}" 2>/dev/null || true)"
if [ -n "$REMOTE_TAG_CHECK" ]; then
    echo "错误: 远程仓库 '${REMOTE_NAME}' 已存在标签 '${TAG_NAME}'。" >&2
    exit 1
fi

COMMIT_MSG="release: release ${TAG_NAME}"

echo ""
echo "发布确认:"
echo "  分支:        $CURRENT_BRANCH"
if [ "$VERSION_CHANGED" = true ]; then
    echo "  versionName: $CURRENT_VERSION -> $TARGET_VERSION"
    echo "  versionCode: $CURRENT_CODE -> $TARGET_CODE"
    echo "  提交信息:    $COMMIT_MSG"
else
    echo "  versionName: $CURRENT_VERSION (不变)"
    echo "  versionCode: $CURRENT_CODE (不变)"
    echo "  提交信息:    (无版本改动，跳过 commit)"
fi
echo "  标签 (Tag):  $TAG_NAME"
echo "  推送目标:    $REMOTE_NAME ($CURRENT_BRANCH + $TAG_NAME)"
echo ""

read -r -p "确认执行以上操作? [y/N]: " CONFIRM
if [[ ! "$CONFIRM" =~ ^[yY]([eE][sS])?$ ]]; then
    echo "已取消发布。"
    exit 0
fi

if [ "$DRY_RUN" = true ]; then
    echo ""
    echo "[dry-run] 演练完成，未执行实际修改或推送。"
    exit 0
fi

# 1. 更新 app/build.gradle 并提交（仅当版本号变化时）
if [ "$VERSION_CHANGED" = true ]; then
    TMP_FILE="$(mktemp)"
    awk -v new_code="$TARGET_CODE" -v new_name="$TARGET_VERSION" '
        BEGIN { updated_code = 0; updated_name = 0 }
        {
            if (!updated_code && $0 ~ /^[[:space:]]*versionCode[[:space:]]+[0-9]+/) {
                sub(/versionCode[[:space:]]+[0-9]+/, "versionCode " new_code)
                updated_code = 1
            } else if (!updated_name && $0 ~ /^[[:space:]]*versionName[[:space:]]+["'\''][^"'\'']+["'\'']/) {
                sub(/versionName[[:space:]]+["'\''][^"'\'']+["'\'']/, "versionName \"" new_name "\"")
                updated_name = 1
            }
            print
        }
        END {
            if (!updated_code || !updated_name) {
                exit 2
            }
        }
    ' "$GRADLE_FILE" > "$TMP_FILE"
    mv "$TMP_FILE" "$GRADLE_FILE"

    git add app/build.gradle
    git commit -m "$COMMIT_MSG"
    echo "已更新 app/build.gradle 并提交: $COMMIT_MSG"
else
    echo "版本号保持不变，跳过修改与提交。"
fi

# 2. 创建附注标签
git tag -a "$TAG_NAME" -m "Release ${TAG_NAME}"
echo "已创建标签: $TAG_NAME"

# 3. 推送分支与标签
echo "正在推送分支 ${CURRENT_BRANCH} 到 ${REMOTE_NAME}..."
git push "$REMOTE_NAME" "$CURRENT_BRANCH"

echo "正在推送标签 ${TAG_NAME} 到 ${REMOTE_NAME}..."
git push "$REMOTE_NAME" "$TAG_NAME"

echo ""
echo "发布推送完成！Tag '${TAG_NAME}' 已推送至 ${REMOTE_NAME}，GitHub Actions Release 工作流将自动触发。"
