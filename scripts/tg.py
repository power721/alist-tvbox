import asyncio
import argparse
from telethon.sync import TelegramClient
from telethon.errors import (
    FloodWaitError, ChannelPrivateError, ChatWriteForbiddenError, RPCError,
    SessionPasswordNeededError, PhoneNumberInvalidError, ApiIdInvalidError
)
import re
import sqlite3
import os
import time
from urllib.parse import unquote
from datetime import datetime, timezone
import logging
import sys

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('telegram_parser.log', mode='a+', encoding='utf-8'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# 切换到脚本所在目录
os.chdir(os.path.dirname(os.path.abspath(__file__)))
logger.debug(f"当前工作目录: {os.getcwd()}")

# 命令行参数
parser = argparse.ArgumentParser(description='Telegram消息提取脚本')
parser.add_argument('--reset', action='store_true', help='清空数据库，强制全新处理')
args = parser.parse_args()

# Telegram配置
api_id = '6627460'
api_hash = '27a53a0965e486a2bc1b1fcde473b1c4'
phone = ''
channels = ['@tgsearchers2', '@tianyirigeng', '@tyysypzypd', '@tyypzhpd', '@cloudtianyi', '@kuakeclound']

# 初始化数据库
db_path = os.getenv('DB_PATH', 'data.db')
conn = sqlite3.connect(db_path, check_same_thread=False)
conn.execute('PRAGMA journal_mode=WAL')
conn.execute('PRAGMA synchronous=NORMAL')
conn.execute('PRAGMA temp_store=MEMORY')
cursor = conn.cursor()

# 清空数据库（--reset）
if args.reset:
    logger.info("清空数据库，准备全新处理")
    try:
        conn.execute('BEGIN TRANSACTION')
        cursor.execute('DELETE FROM last_processed_messages')
        cursor.execute('DELETE FROM x_storages')
        conn.commit()
        logger.info("数据库已重置")
    except sqlite3.Error as e:
        conn.rollback()
        logger.error(f"清空数据库失败: {str(e)}")
        raise


# 创建表并添加索引
def init_database():
    """初始化数据库表结构"""
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS last_processed_messages (
            channel TEXT PRIMARY KEY,
            last_message_id INTEGER
        )
    ''')
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS x_storages (
            id INTEGER PRIMARY KEY,
            mount_path TEXT,
            "order" INTEGER,
            driver TEXT,
            cache_expiration INTEGER,
            status TEXT,
            addition TEXT,
            remark TEXT,
            disabled INTEGER,
            order_by TEXT,
            order_direction TEXT,
            web_proxy INTEGER,
            webdav_policy TEXT,
            down_proxy_url TEXT,
            disable_index INTEGER,
            enable_sign INTEGER,
            proxy_range INTEGER,
            modified TEXT,
            extract_folder TEXT
        )
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_x_storages_mount_path
        ON x_storages(mount_path)
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_x_storages_addition
        ON x_storages(addition)
    ''')
    conn.commit()


init_database()

# 获取最大ID
cursor.execute('SELECT MAX(id) FROM x_storages')
max_id = cursor.fetchone()[0]
current_id = 30000 if max_id is None or max_id < 30000 else max_id + 1
logger.info(f"当前起始ID: {current_id}")

# 主正则表达式
MASTER_PATTERN = re.compile(
    r'(?P<title>名称：\s*(.*?))(?=\n*(?:https?://|\[【\(|\]】\)|\[|🏷|_标签：|$))|'
    r'(?P<url>https?://cloud\.189\.cn(?:/web)?/(?:share\.html\?shareId=|t/|web/shareDetail\.do\?sid=|s/)(?P<share_id>[a-zA-Z0-9\-_]+))|'
    r'(?:访问码|提取码|密码|验证码)[：:\s]*(?P<code>[a-zA-Z0-9]{4})',  # 调整为仅捕获4位码
    re.DOTALL | re.MULTILINE | re.IGNORECASE
)


# 替换空格
def replace_spaces(title):
    """将标题中的空格替换为下划线"""
    return title.replace(' ', '_')


# 获取唯一 mount_path
def get_unique_mount_path(base_path, used_paths):
    """基于已使用路径生成唯一 mount_path"""
    if base_path not in used_paths:
        used_paths.add(base_path)
        return base_path
    counter = 1
    while True:
        new_path = f"{base_path}_{counter}"
        if new_path not in used_paths:
            used_paths.add(new_path)
            return new_path
        counter += 1


# 获取日期文件名
def get_date_filename():
    """生成日期文件名，如 189share-06-10.txt"""
    return f"189share-{datetime.now(timezone.utc).strftime('%m-%d')}.txt"


# 批量写入文件
def batch_save_to_text(records):
    """批量保存记录到文件，防止重复"""
    if not records:
        return

    date_file = f"{get_date_filename()}"
    # 移除不必要的目录创建，因为文件直接保存到当前目录
    try:
        with open(date_file, 'a', encoding='utf-8') as f:
            for mount_path, share_id, share_pwd in records:
                f.write(f"{mount_path} 9:{share_id} root {share_pwd}\n")
        logger.info(f"批量写入 {len(records)} 条记录到 {date_file}")
    except Exception as e:
        logger.error(f"写入文件失败: {str(e)}")


# 分类函数
def should_put_in_active_folder(message_text, title):
    """
    仅根据 active_keywords 判断活跃资源，非 active_keywords 归类为天翼完结
    """
    combined = f"{title} {message_text}".lower()
    active_keywords = ["更新中", "更新至", "更至", "首更", "连载中", "持续更新", "周更", "日更"]

    for kw in active_keywords:
        if kw in combined:
            logger.debug(f"判断为活跃资源，依据: {kw}")
            return True

    logger.debug("判断为完结资源，无活跃标记")
    return False


# 提取标题
def extract_title(text):
    """从消息文本中提取标题"""
    for match in MASTER_PATTERN.finditer(text):
        if match.group('title'):
            title = match.group('title').strip()
            if title:
                return unquote(title)

    for line in text.split('\n'):
        line = line.strip()
        if line and not line.startswith(('http', 'https', '🏷', '_标签：', '简介：', '分享：')):
            return unquote(line)

    url_match = MASTER_PATTERN.search(text)
    if url_match and url_match.group('url'):
        return f"未命名资源_{url_match.group('share_id')}"

    return f"未命名资源_{int(time.time())}"


# 提取链接和访问码
def extract_links(text):
    """提取所有天翼链接和对应的访问码"""
    links = []
    last_url_index = -1

    for match in MASTER_PATTERN.finditer(text):
        if match.group('url'):
            share_id = match.group('share_id')
            # 使用前一个访问码（如果存在）
            code = links[last_url_index][1] if last_url_index >= 0 else ""
            links.append((share_id, code))
            last_url_index += 1
        elif match.group('code'):
            code = match.group('code')  # 直接使用捕获的4位码
            if last_url_index >= 0:
                links[last_url_index] = (links[last_url_index][0], code)  # 更新前一个链接的访问码

    return links


# 处理单个消息
def process_message(message, cursor, processed_share_codes, existing_records, current_id):
    """处理单条消息，返回新记录、文本记录和待删除ID"""
    if not message.text or not message.text.strip():
        logger.debug(f"消息 ID: {message.id} 无文本，跳过")
        return [], [], []

    try:
        raw_title = extract_title(message.text)
        clean_title = raw_title.replace('*', '').strip()
        if clean_title.startswith('名称：'):
            clean_title = clean_title[3:].strip()
        clean_title = re.sub(r'[)\]\}]+$', '', clean_title).strip()
        clean_title = replace_spaces(clean_title)
        # 移除多种多余斜杠和连续下划线
        clean_title = re.sub(r'__+|/[_/]*', '_', clean_title)
        # 去除首尾多余的 _ 或 /
        clean_title = clean_title.strip('_/ ')
        logger.debug(f"提取标题: {clean_title}")

        links = extract_links(message.text)
        if not links:
            logger.debug(f"消息 ID: {message.id} 无天翼链接，跳过")
            return [], [], []

        folder = "天翼分享" if should_put_in_active_folder(message.text, clean_title) else "天翼完结"
        base_mount_path = f"/{folder}/{clean_title}"

        new_records = []
        text_records = []
        delete_ids = set()
        used_paths = {}  # 存储分享码对应的第一个 mount_path

        for i, (share_id, share_code) in enumerate(links):
            # 跳过已处理的分享码（跨频道去重）
            if share_id in processed_share_codes:
                logger.debug(f"跳过已处理分享码: {share_id}")
                continue

            processed_share_codes.add(share_id)

            # 使用第一个出现的 mount_path
            if share_id not in used_paths:
                mount_path = get_unique_mount_path(base_mount_path, set(used_paths.values()))
                used_paths[share_id] = mount_path
            else:
                mount_path = used_paths[share_id]

            logger.debug(f"Generated mount_path for {share_id}: {mount_path}")

            record_id = current_id + len(new_records)
            record = (
                record_id,
                '/我的天翼分享' + mount_path,
                0,
                '189Share',
                30,
                'work',
                f'{{"share_id":"{share_id}","share_pwd":"{share_code}","ShareToken":"","root_folder_id":""}}',
                '',
                0,
                'name',
                'ASC',
                0,
                '302_redirect',
                '',
                0,
                0,
                0,
                datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S+00:00'),
                ''
            )
            new_records.append(record)
            text_records.append((mount_path, share_id, share_code))
            logger.info(
                f"准备新记录: ID={record_id}, 分享码: {share_id}, 访问码: {share_code}, mount_path: {mount_path}")

            # 如果分享码已存在数据库，标记删除旧记录
            if share_id in existing_records and share_id not in processed_share_codes:  # 仅在新添加时删除
                delete_ids.add(existing_records[share_id])
                del existing_records[share_id]
            existing_records[share_id] = record_id

        return new_records, text_records, list(delete_ids)

    except Exception as e:
        logger.error(f"处理消息 ID: {message.id} 失败: {str(e)}")
        return [], [], []


# 批量插入记录
def batch_insert_records(records):
    """批量插入记录到数据库"""
    if not records:
        return 0

    try:
        conn.execute('BEGIN TRANSACTION')
        cursor.executemany('''
            INSERT OR IGNORE INTO x_storages (
                id, mount_path, "order", driver, cache_expiration,
                status, addition, remark, disabled, order_by,
                order_direction, web_proxy, webdav_policy, down_proxy_url,
                disable_index, enable_sign, proxy_range, modified, extract_folder
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''', records)
        conn.commit()
        inserted = cursor.rowcount
        logger.info(f"成功插入 {inserted} 条记录")
        return inserted
    except sqlite3.IntegrityError as e:
        conn.rollback()
        logger.warning(f"批量插入失败: {str(e)}，尝试逐条插入")
        return insert_one_by_one(records)
    except sqlite3.Error as e:
        conn.rollback()
        logger.error(f"数据库操作失败: {str(e)}")
        return 0


def insert_one_by_one(records):
    """逐条插入记录"""
    inserted = 0
    for record in records:
        try:
            cursor.execute('''
                INSERT OR IGNORE INTO x_storages (
                    id, mount_path, "order", driver, cache_expiration,
                    status, addition, remark, disabled, order_by,
                    order_direction, web_proxy, webdav_policy, down_proxy_url,
                    disable_index, enable_sign, proxy_range, modified, extract_folder
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', record)
            conn.commit()
            inserted += 1
        except sqlite3.Error as e:
            conn.rollback()
            logger.error(f"插入记录失败: {str(e)}")
    return inserted


# Telegram客户端
client = TelegramClient('session', api_id, api_hash)


async def main():
    global current_id
    try:
        await client.start(phone)
        logger.info("Telegram客户端启动成功")
    except (SessionPasswordNeededError, PhoneNumberInvalidError, ApiIdInvalidError) as e:
        logger.error(f"客户端启动失败: {str(e)}")
        sys.exit(1)

    processed_share_codes = set()
    existing_records = {}
    start_time = time.time()

    # 加载现有数据库记录
    if not args.reset:
        try:
            cursor.execute('''
                SELECT id, json_extract(addition, '$.share_id')
                FROM x_storages
                WHERE driver = '189Share'
            ''')
            for row in cursor.fetchall():
                share_id = row[1]
                if share_id:
                    processed_share_codes.add(share_id)
                    existing_records[share_id] = row[0]
            logger.info(f"加载 {len(existing_records)} 条记录，耗时: {time.time() - start_time:.2f}秒")
        except sqlite3.Error as e:
            logger.error(f"加载记录失败: {str(e)}")

    for channel in channels:
        logger.info(f"开始处理频道: {channel}")

        cursor.execute('SELECT last_message_id FROM last_processed_messages WHERE channel = ?', (channel,))
        result = cursor.fetchone()
        last_message_id = result[0] if result else None

        min_id = last_message_id + 1 if last_message_id else 0
        message_limit = 100000
        reverse = False

        try:
            total_messages = await client.get_messages(channel, limit=1)
            if not total_messages or (last_message_id and total_messages[0].id <= last_message_id and not args.reset):
                logger.info(f"频道 {channel} 无新消息，跳过处理")
                continue

            processed_count = 0
            max_id_in_batch = 0
            to_delete_ids = set()
            new_records_batch = []
            text_records_batch = []
            batch_start = time.time()

            async for message in client.iter_messages(
                    channel,
                    min_id=min_id,
                    limit=message_limit,
                    reverse=reverse,
                    wait_time=0.1  # 减少等待时间
            ):
                if max_id_in_batch == 0:
                    max_id_in_batch = message.id

                new_records, text_records, delete_ids = process_message(
                    message, cursor, processed_share_codes, existing_records, current_id
                )
                to_delete_ids.update(delete_ids)

                if new_records:
                    new_records_batch.extend(new_records)
                    text_records_batch.extend(text_records)
                    current_id += len(new_records)
                    processed_count += len(new_records)

                # 每100条检查处理速度
                if processed_count % 100 == 0:
                    elapsed = time.time() - batch_start
                    if elapsed < 0.5:  # 每秒超过200条
                        sleep_time = min(0.5, (0.5 - elapsed) * 2)
                        logger.debug(f"处理速度过快，休眠 {sleep_time:.2f} 秒")
                        await asyncio.sleep(sleep_time)

                # 每500条批量处理
                if processed_count >= 500:
                    batch_insert_records(new_records_batch)
                    batch_save_to_text(text_records_batch)
                    new_records_batch.clear()
                    text_records_batch.clear()
                    processed_count = 0
                    batch_start = time.time()

            # 处理剩余记录
            if new_records_batch:
                batch_insert_records(new_records_batch)
                batch_save_to_text(text_records_batch)
                logger.info(f"处理剩余 {len(new_records_batch)} 条记录")

            # 批量删除优化
            if to_delete_ids:
                id_chunks = [list(to_delete_ids)[i:i + 500] for i in range(0, len(to_delete_ids), 500)]
                for chunk in id_chunks:
                    placeholders = ','.join('?' for _ in chunk)
                    cursor.execute(f'DELETE FROM x_storages WHERE id IN ({placeholders})', chunk)
                conn.commit()
                logger.info(f"从数据库删除 {len(to_delete_ids)} 条旧记录")

            if max_id_in_batch > 0:
                cursor.execute('''
                    INSERT OR REPLACE INTO last_processed_messages (channel, last_message_id)
                    VALUES (?, ?)
                ''', (channel, max_id_in_batch))
                conn.commit()
                logger.info(f"更新检查点: {channel} 最后ID={max_id_in_batch}")

            logger.info(
                f"频道 {channel} 处理完成，处理 {processed_count} 条记录，总耗时: {time.time() - start_time:.2f}秒")

        except FloodWaitError as e:
            wait_time = e.seconds + 10
            logger.warning(f"触发风控，等待 {wait_time} 秒")
            await asyncio.sleep(wait_time)
        except (ChannelPrivateError, ChatWriteForbiddenError) as e:
            logger.error(f"频道访问失败: {str(e)}")
        except RPCError as e:
            logger.warning(f"API错误: {str(e)}")
            await asyncio.sleep(5)
        except Exception as e:
            logger.error(f"处理频道 {channel} 失败: {str(e)}", exc_info=True)


if __name__ == '__main__':
    try:
        with client:
            client.loop.run_until_complete(main())
    except Exception as e:
        logger.error(f"脚本运行错误: {str(e)}", exc_info=True)
    finally:
        logger.info("执行数据库优化...")
        try:
            cursor.execute("PRAGMA optimize")
            conn.commit()
        except Exception as e:
            logger.error(f"数据库优化失败: {str(e)}")
        finally:
            conn.close()
            logger.info("数据库连接已关闭")