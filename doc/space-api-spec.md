# 空间模块 API 接口文档

> 文档版本:v1.0(2026-08-09)
> 面向:后端开发(独立代码仓实现空间模块接口)
> 依据:前端 `gif-viewer/panels/space.js` 当前打桩数据设计,确保接口字段与前端展示需求一一对应。

---

## 一、概述

### 1.1 协议与认证

- 基础地址:`http://<host>:3000/api/spaces`(建议空间接口统一使用 `/api/spaces` 前缀,与其它模块隔离)
- 认证方式:JWT,请求头 `Authorization: Bearer <token>`,除公开浏览接口外均需登录
- 数据格式:JSON,`Content-Type: application/json`;文件上传使用 `multipart/form-data`

### 1.2 响应规范

- **成功**:直接返回业务数据(与现有 server 风格一致,不额外包裹)
- **失败**:HTTP 状态码 + `{ "error": "中文错误信息" }`

### 1.3 通用分页

分页接口统一使用 `page`(从 1 开始)+ `limit`(默认 20,最大 50)查询参数,响应统一为:

```json
{
  "posts": [],
  "total": 0,
  "page": 1
}
```

### 1.4 通用字段说明

- `created_at`:ISO 8601 时间字符串(如 `2026-08-09T10:00:00.000Z`),前端负责格式化为相对时间
- 时间字段统一用 UTC 存储

---

## 二、数据模型(后端建表参考)

### 2.1 帖子 Post(个人空间 feed)

对应前端打桩 `samplePosts` 每条记录。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id | BIGINT 自增 | - | 主键 |
| user_id | BIGINT | ✅ | 作者 |
| content | TEXT | ✅ | 正文(前端输入框 maxlength 300) |
| status | TINYINT | - | 1=公开 0=草稿 2=删除,默认 1 |
| like_count | INT | - | 点赞数,默认 0 |
| comment_count | INT | - | 评论数,默认 0 |
| view_count | INT | - | 浏览数,默认 0(可选) |
| created_at | DATETIME | - | 创建时间 |
| deleted_at | DATETIME | - | 软删除时间(可选) |

### 2.2 帖子图片 PostImage(发帖最多 4 张)

前端打桩里 `images`(数量)+ `imageFiles`(dataURL),真实后端应拆分为图片资源列表。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id | BIGINT 自增 | - | 主键 |
| post_id | BIGINT | ✅ | 所属帖子 |
| url | STRING(500) | ✅ | 图片访问地址 |
| thumbnail_url | STRING(500) | - | 缩略图地址 |
| width | INT | - | 宽 |
| height | INT | - | 高 |
| sort_order | INT | - | 排序,默认 0 |

> 也可复用现有 `resources` 表(type=1 图片)加一张 `post_images` 关联表;两种方式由后端权衡,接口契约不受影响。

### 2.3 社交卡片 SocialCard(帖子空间)

对应前端打桩 `sampleCards` 每条记录。**本质就是一条「帖子」的展示视图**,后端可从 Post 表投影生成,无需单独建表。

| 字段 | 说明 | 对应打桩 |
|---|---|---|
| id | 帖子 id | - |
| title | 卡片标题 | `title` |
| cover_image_url | 封面图(打桩用渐变 `color` 占位,真实应换成图片) | `color` |
| like_count | 点赞数 | `likes` |
| author.nickname | 作者昵称 | `author` |
| author.avatar_url | 作者头像 | - |

### 2.4 收藏 Bookmark

对应前端打桩 `bookmarks` 数组(每项 `{ title, url }`)。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id | BIGINT 自增 | - | 主键 |
| user_id | BIGINT | ✅ | 归属用户 |
| title | STRING(100) | ✅ | 标题(前端输入 maxlength 50) |
| url | STRING(500) | ✅ | 网址(前端输入 maxlength 500) |
| created_at | DATETIME | - | 创建时间 |

---

## 三、接口清单总表

| # | 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|---|
| 1 | POST | `/api/spaces/upload` | ✅ | 图片上传(返回 url 供发帖用) |
| 2 | POST | `/api/spaces/posts` | ✅ | 发布帖子(文本 + 最多 4 图) |
| 3 | GET | `/api/spaces/posts` | 可选 | 帖子流(个人空间) |
| 4 | GET | `/api/spaces/posts/:id` | 可选 | 帖子详情 |
| 5 | POST | `/api/spaces/posts/:id/like` | ✅ | 点赞 |
| 6 | DELETE | `/api/spaces/posts/:id/like` | ✅ | 取消点赞 |
| 7 | GET | `/api/spaces/posts/:id/comments` | 可选 | 评论列表 |
| 8 | POST | `/api/spaces/posts/:id/comments` | ✅ | 发表评论 |
| 9 | GET | `/api/spaces/social` | 可选 | 社交卡片流(帖子空间) |
| 10 | GET | `/api/spaces/bookmarks` | ✅ | 收藏列表 |
| 11 | POST | `/api/spaces/bookmarks` | ✅ | 添加收藏 |
| 12 | DELETE | `/api/spaces/bookmarks/:id` | ✅ | 删除收藏 |

---

## 四、接口详情

### 4.1 图片上传

```
POST /api/spaces/upload
Content-Type: multipart/form-data
表单字段: file(单文件)
```

**说明**:支持 jpg/jpeg/png/gif/bmp/webp;单文件大小上限建议 10MB(可按需调整);服务端返回图片访问地址。

**请求**(multipart):

```
file: <binary>
```

**响应 200**:

```json
{
  "id": 101,
  "url": "/api/files/xxxx-xxxx.png",
  "thumbnail_url": "/api/files/xxxx-xxxx_thumb.png",
  "width": 800,
  "height": 600,
  "size": 204800
}
```

**失败**:`413 { "error": "文件大小超出限制" }` / `400 { "error": "不支持的文件类型" }`

---

### 4.2 发布帖子

```
POST /api/spaces/posts
Content-Type: application/json
```

**请求体**:

```json
{
  "content": "今天天气真好，出去散步了！随手拍了几张照片，分享给大家看看～",
  "images": ["/api/files/xxxx-1.png", "/api/files/xxxx-2.png"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| content | string | ✅ | 正文,1-300 字 |
| images | string[] | 否 | 上传接口返回的 url 数组,最多 4 张 |

**响应 201**:

```json
{
  "id": 42,
  "author": { "id": 1, "nickname": "张三", "avatar_url": "/api/files/avatar.png" },
  "content": "今天天气真好，出去散步了！随手拍了几张照片，分享给大家看看～",
  "images": [
    { "url": "/api/files/xxxx-1.png", "thumbnail_url": null, "width": 800, "height": 600 }
  ],
  "like_count": 0,
  "comment_count": 0,
  "created_at": "2026-08-09T10:00:00.000Z"
}
```

**失败**:`400 { "error": "内容不能为空" }` / `401 未认证`

---

### 4.3 帖子流(个人空间)

```
GET /api/spaces/posts?page=1&limit=20&userId=1
```

| 查询参数 | 类型 | 说明 |
|---|---|---|
| page | int | 页码,默认 1 |
| limit | int | 每页条数,默认 20 |
| userId | int | 可选;不传返回公开时间流,传了返回该用户的空间(个人空间) |

**响应 200**:

```json
{
  "posts": [
    {
      "id": 42,
      "author": { "id": 1, "nickname": "张三", "avatar_url": "/api/files/avatar.png" },
      "content": "今天天气真好，出去散步了！随手拍了几张照片，分享给大家看看～",
      "images": [
        { "url": "/api/files/xxxx-1.png", "thumbnail_url": null, "width": 800, "height": 600 }
      ],
      "like_count": 12,
      "comment_count": 3,
      "created_at": "2026-08-09T10:00:00.000Z"
    }
  ],
  "total": 128,
  "page": 1
}
```

**排序**:按 `created_at` 倒序(最新在前)。

---

### 4.4 帖子详情

```
GET /api/spaces/posts/:id
```

**响应 200**:同 4.2 的帖子对象结构(含完整 images 列表)。

**失败**:`404 { "error": "帖子不存在" }`

---

### 4.5 点赞 / 取消点赞

```
POST   /api/spaces/posts/:id/like
DELETE /api/spaces/posts/:id/like
```

**响应 200**(两个接口一致,返回最新计数供前端刷新):

```json
{
  "liked": true,
  "like_count": 13
}
```

`DELETE` 成功时 `liked` 为 `false`。**建议**:接口返回当前最新 `like_count`,前端直接覆盖,避免二次查询。

**失败**:`404 { "error": "帖子不存在" }` / `401 未认证`

---

### 4.6 评论列表 / 发表评论

```
GET  /api/spaces/posts/:id/comments?page=1&limit=20
POST /api/spaces/posts/:id/comments
```

**GET 响应 200**:

```json
{
  "comments": [
    {
      "id": 1,
      "author": { "id": 2, "nickname": "李四", "avatar_url": null },
      "content": "拍得真好！",
      "like_count": 0,
      "created_at": "2026-08-09T11:00:00.000Z"
    }
  ],
  "total": 1,
  "page": 1
}
```

**POST 请求体**:

```json
{ "content": "拍得真好！" }
```

**POST 响应 201**:创建的评论对象(结构同上)。

**失败**:`400 { "error": "内容不能为空" }` / `404 { "error": "帖子不存在" }`

> 说明:打桩数据只展示评论「数量」,未展示评论列表页;评论接口为浏览帖子必需,故一并纳入。如后端已有通用评论模块,可复用。

---

### 4.7 社交卡片流(帖子空间)

```
GET /api/spaces/social?page=1&limit=20
```

**说明**:帖子空间的瀑布流卡片。第一版按时间倒序取公开帖子投影即可,后续可加热度排序。

**响应 200**:

```json
{
  "cards": [
    {
      "id": 42,
      "title": "城市漫步 | 发现隐藏的特色小店",
      "cover_image_url": "/api/files/cover.png",
      "like_count": 1234,
      "author": { "nickname": "旅行者", "avatar_url": null }
    }
  ],
  "total": 66,
  "page": 1
}
```

> `title` 可取自帖子标题;打桩里卡片没有正文,展示层只显示标题 + 封面 + 点赞数 + 作者。若帖子无标题,`title` 可由 `content` 截断生成(前端也可直接展示 content)。

---

### 4.8 收藏列表

```
GET /api/spaces/bookmarks
```

**响应 200**:

```json
{
  "bookmarks": [
    { "id": 1, "title": "某收藏网站", "url": "https://example.com", "created_at": "2026-08-09T09:00:00.000Z" }
  ]
}
```

---

### 4.9 添加收藏

```
POST /api/spaces/bookmarks
Content-Type: application/json
```

**请求体**:

```json
{ "title": "某收藏网站", "url": "https://example.com" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| title | string | ✅ | 1-50 字 |
| url | string | ✅ | 1-500 字,需为有效 http/https 链接 |

**响应 201**:

```json
{ "id": 1, "title": "某收藏网站", "url": "https://example.com", "created_at": "2026-08-09T09:00:00.000Z" }
```

**失败**:`400 { "error": "标题和网址不能为空" }`

---

### 4.10 删除收藏

```
DELETE /api/spaces/bookmarks/:id
```

**响应 200**:

```json
{ "message": "删除成功" }
```

**失败**:`404 { "error": "收藏不存在" }`(仅本人可删,他人收藏返回 `403`)

---

## 五、错误码汇总

| 状态码 | 含义 | 示例错误信息 |
|---|---|---|
| 400 | 参数错误 | "内容不能为空" / "标题和网址不能为空" |
| 401 | 未认证/令牌失效 | "未提供认证令牌" / "令牌已过期" |
| 403 | 无权操作 | "无权删除此收藏" |
| 404 | 资源不存在 | "帖子不存在" / "收藏不存在" |
| 413 | 上传过大 | "文件大小超出限制" |

---

## 六、前端打桩数据 → 接口字段映射

帮助后端理解前端「真正展示什么」,后端按此返回即可让打桩页面无痛切换为真实数据。

| 打桩字段 | 展示位置 | 后端字段来源 |
|---|---|---|
| `name` | 帖子作者名 | `author.nickname` |
| `avatar` | 作者头像(首字) | `author.avatar_url`(为空前端显示首字) |
| `time`("2小时前"/"昨天"/日期) | 相对时间 | `created_at`(前端格式化) |
| `text` | 帖子正文 | `content` |
| `likes` | ❤ 点赞数 | `like_count` |
| `comments` | 💬 评论数 | `comment_count` |
| `images` / `imageFiles` | 帖子图片区(最多 4 张) | `images[].url` |
| 卡片 `title` | 卡片标题 | `title`(无标题则截断 content) |
| 卡片 `likes`(1234→1.2k) | ❤ 点赞数 | `like_count` |
| 卡片 `author` | 👤 作者 | `author.nickname` |
| 卡片 `color`(渐变占位) | 卡片封面 | `cover_image_url`(真实图片替代渐变) |
| 收藏 `title` / `url` | 侧栏书签项 | `bookmarks[].title` / `.url` |

---

## 七、与现有 server 的关系(可选复用对照)

若选择在当前 `server` 仓复用而非另起炉灶,以下已有资产可直接用:

| 新接口 | 现有可复用 |
|---|---|
| `POST /api/spaces/upload` | `POST /api/resources/upload`(已支持 MD5 去重、图片/视频) |
| 帖子模型 | 现有 `Post` 模型(字段高度吻合,需补 images 关联) |
| 点赞 | 现有 `Like` + `POST /api/likes` |
| 收藏 | 现有 `Favorite` 模型(结构与空间收藏略不同,空间收藏更贴近书签) |
| 评论 | 现有 `Comment` + `POST /api/comments`(已支持嵌套) |

若在新仓独立开发,按本文档第二章建模即可,接口契约一致,前端无需区分后端实现。
