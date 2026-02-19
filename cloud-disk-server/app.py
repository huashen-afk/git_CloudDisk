# 个人云盘后端服务器
# 这是一个完整的、可以直接运行的服务器程序

# 导入必要的工具包
import os
import hashlib
import uuid
import secrets
import shutil
import socket
import urllib.request
import urllib.error
from datetime import datetime, timedelta
from flask import Flask, request, jsonify, send_from_directory
from flask_sqlalchemy import SQLAlchemy
from flask_bcrypt import Bcrypt
from flask_cors import CORS
from flask_jwt_extended import (
    JWTManager, create_access_token, jwt_required, 
    get_jwt_identity, get_jwt
)
from werkzeug.utils import secure_filename
import json
from pathlib import Path  # 添加这行

# ========== 第一步：创建Flask应用 ==========
app = Flask(__name__)  # 创建应用实例
CORS(app)  # 允许跨域访问（这样安卓应用才能连接）

# ========== 第二步：配置应用设置 ==========
# 获取当前文件所在的目录（项目根目录）
BASE_DIR = Path(__file__).parent.absolute()

# 确保uploads目录存在
UPLOADS_DIR = BASE_DIR / "uploads"
DATABASE_DIR = BASE_DIR / "data"  # 新建一个data目录存放数据库

# 创建必要的目录
UPLOADS_DIR.mkdir(exist_ok=True, parents=True)
DATABASE_DIR.mkdir(exist_ok=True, parents=True)

# 这些是应用的设置
app.config['SECRET_KEY'] = 'my-cloud-disk-secret-key-123456'  # 安全密钥
app.config['JWT_SECRET_KEY'] = 'my-jwt-secret-key-123456'  # 令牌密钥
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = timedelta(hours=24)  # 令牌有效期24小时
app.config['JWT_HEADER_NAME'] = 'Authorization'  # JWT头名称
app.config['JWT_HEADER_TYPE'] = 'Bearer'  # JWT头类型

# 使用绝对路径配置数据库和上传目录
app.config['SQLALCHEMY_DATABASE_URI'] = f'sqlite:///{DATABASE_DIR}/cloud_disk.db'  # 修改这里
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
app.config['UPLOAD_FOLDER'] = str(UPLOADS_DIR)  # 修改这里
# 上传体积限制（默认100MB）；可根据需要调大
# 将限制提升到1GB，避免大文件上传返回413
app.config['MAX_CONTENT_LENGTH'] = 1024 * 1024 * 1024

# 允许上传的文件类型
app.config['ALLOWED_EXTENSIONS'] = {
    'txt', 'pdf', 'png', 'jpg', 'jpeg', 'gif', 'bmp', 
    'mp4', 'avi', 'mov', 'mp3', 'wav', 'zip', 'rar', 
    'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'
}

# ========== 第三步：初始化扩展 ==========
db = SQLAlchemy(app)   # 数据库
bcrypt = Bcrypt(app)    # 密码加密
jwt = JWTManager(app)   # 令牌管理

# JWT错误处理
@jwt.expired_token_loader
def expired_token_callback(jwt_header, jwt_payload):
    return jsonify({
        'success': False,
        'message': None,
        'data': None,
        'error': '令牌已过期，请重新登录'
    }), 401

@jwt.invalid_token_loader
def invalid_token_callback(error):
    from flask import request
    auth_header = request.headers.get('Authorization', '')
    print(f"[错误] 无效令牌错误: {str(error)}")
    print(f"[错误] Authorization头: {auth_header[:100] if auth_header else 'None'}")
    return jsonify({
        'success': False,
        'message': None,
        'data': None,
        'error': f'无效的令牌: {str(error)}'
    }), 422

@jwt.unauthorized_loader
def missing_token_callback(error):
    return jsonify({
        'success': False,
        'message': None,
        'data': None,
        'error': '缺少认证令牌，请先登录'
    }), 401

# ========== 第四步：定义数据表 ==========
# 就像在Excel中创建表格一样，我们定义数据库的表格结构

class User(db.Model):
    """用户表 - 存储用户信息"""
    __tablename__ = 'users'  # 表名
    
    # 定义表的列
    id = db.Column(db.Integer, primary_key=True)  # 用户ID，主键
    username = db.Column(db.String(80), unique=True, nullable=False)  # 用户名，不能重复
    email = db.Column(db.String(120), unique=True, nullable=False)  # 邮箱，不能重复
    password_hash = db.Column(db.String(200), nullable=False)  # 加密后的密码
    avatar_url = db.Column(db.String(255), nullable=True)  # 头像URL
    created_at = db.Column(db.DateTime, default=datetime.utcnow)  # 创建时间
    
    def set_password(self, password):
        """设置密码（自动加密）"""
        self.password_hash = bcrypt.generate_password_hash(password).decode('utf-8')
    
    def check_password(self, password):
        """验证密码"""
        return bcrypt.check_password_hash(self.password_hash, password)
    
    def to_dict(self):
        """转换为字典格式，方便返回给前端"""
        return {
            'id': self.id,
            'username': self.username,
            'email': self.email,
            'avatar_url': self.avatar_url,
            'created_at': self.created_at.isoformat() if self.created_at else None
        }

class File(db.Model):
    """文件表 - 存储文件信息"""
    __tablename__ = 'files'
    
    id = db.Column(db.Integer, primary_key=True)  # 文件ID
    filename = db.Column(db.String(255), nullable=False)  # 存储的文件名（UUID）
    original_filename = db.Column(db.String(255), nullable=False)  # 原始文件名
    file_size = db.Column(db.Integer, nullable=False)  # 文件大小（字节）
    file_hash = db.Column(db.String(64), nullable=False)  # 文件哈希值
    file_type = db.Column(db.String(50))  # 文件类型
    upload_date = db.Column(db.DateTime, default=datetime.utcnow)  # 上传时间
    share_token = db.Column(db.String(32), unique=True, nullable=True)  # 分享令牌
    share_expiry = db.Column(db.DateTime, nullable=True)  # 分享过期时间
    owner_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)  # 所属用户ID
    folder_id = db.Column(db.Integer, db.ForeignKey('folders.id'), nullable=True)  # 所属文件夹ID
    
    # 定义关系
    owner = db.relationship('User', backref='files', lazy=True)
    
    def generate_share_token(self, days=7):
        """生成分享令牌"""
        self.share_token = secrets.token_urlsafe(16)  # 生成16个字符的随机令牌
        self.share_expiry = datetime.utcnow() + timedelta(days=days)  # 7天后过期
        return self.share_token
    
    def to_dict(self):
        """转换为字典格式"""
        try:
            return {
                'id': self.id,
                'filename': self.original_filename,
                'file_size': self.file_size,
                'file_type': self.file_type,
                'upload_date': self.upload_date.isoformat() if self.upload_date else None,
                'share_token': self.share_token,
                'share_expiry': self.share_expiry.isoformat() if self.share_expiry else None,
                'owner_id': self.owner_id,
                'folder_id': getattr(self, 'folder_id', None)  # 安全获取folder_id，如果字段不存在则返回None
            }
        except Exception as e:
            print(f"[错误] File.to_dict() 错误 (文件ID: {self.id}): {str(e)}")
            import traceback
            print(traceback.format_exc())
            raise

class Folder(db.Model):
    """文件夹表 - 存储文件夹信息"""
    __tablename__ = 'folders'
    
    id = db.Column(db.Integer, primary_key=True)  # 文件夹ID
    folder_name = db.Column(db.String(255), nullable=False)  # 文件夹名称
    created_date = db.Column(db.DateTime, default=datetime.utcnow)  # 创建时间
    owner_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)  # 所属用户ID
    parent_folder_id = db.Column(db.Integer, db.ForeignKey('folders.id'), nullable=True)  # 父文件夹ID
    share_token = db.Column(db.String(32), unique=True, nullable=True)  # 分享令牌
    share_expiry = db.Column(db.DateTime, nullable=True)  # 分享过期时间
    
    # 定义关系
    owner = db.relationship('User', backref='folders', lazy=True)
    
    def generate_share_token(self, days=7):
        """生成分享令牌"""
        self.share_token = secrets.token_urlsafe(16)  # 生成16个字符的随机令牌
        self.share_expiry = datetime.utcnow() + timedelta(days=days)  # 7天后过期
        return self.share_token
    
    def to_dict(self):
        """转换为字典格式"""
        return {
            'id': self.id,
            'folder_name': self.folder_name,
            'created_date': self.created_date.isoformat() if self.created_date else None,
            'owner_id': self.owner_id,
            'parent_folder_id': self.parent_folder_id,
            'share_token': self.share_token,
            'share_expiry': self.share_expiry.isoformat() if self.share_expiry else None
        }

# ========== 第五步：辅助函数 ==========

def get_current_user():
    """获取当前登录用户信息（从JWT令牌中解析）"""
    current_user_str = get_jwt_identity()
    return json.loads(current_user_str)

def allowed_file(filename):
    """检查文件扩展名是否允许（放宽限制，允许大部分常见格式）"""
    if '.' not in filename:
        # 如果没有扩展名，允许上传（可能是无扩展名的文件）
        return True
    file_extension = filename.rsplit('.', 1)[1].lower()
    # 允许所有常见格式，包括但不限于：
    # 文本：txt, md, log, csv, json, xml, html, css, js
    # 图片：jpg, jpeg, png, gif, bmp, webp, svg, ico
    # 视频：mp4, avi, mov, mkv, wmv, flv, webm
    # 音频：mp3, wav, flac, aac, ogg, m4a
    # 文档：pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp
    # 压缩：zip, rar, 7z, tar, gz, bz2
    # 其他：apk, exe, dmg, iso, bin
    common_extensions = {
        # 文本文件
        'txt', 'md', 'log', 'csv', 'json', 'xml', 'html', 'htm', 'css', 'js', 'jsx', 'ts', 'tsx',
        'py', 'java', 'cpp', 'c', 'h', 'hpp', 'cs', 'php', 'rb', 'go', 'rs', 'swift', 'kt',
        'sh', 'bat', 'cmd', 'ps1', 'yml', 'yaml', 'ini', 'cfg', 'conf', 'properties',
        # 图片文件
        'jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico', 'tiff', 'tif', 'heic', 'heif',
        # 视频文件
        'mp4', 'avi', 'mov', 'mkv', 'wmv', 'flv', 'webm', 'm4v', '3gp', 'mpg', 'mpeg', 'rm', 'rmvb',
        # 音频文件
        'mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a', 'wma', 'opus', 'amr',
        # 文档文件
        'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'odt', 'ods', 'odp', 'rtf',
        # 压缩文件
        'zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'z', 'cab', 'iso',
        # 可执行文件
        'apk', 'exe', 'dmg', 'pkg', 'deb', 'rpm', 'msi',
        # 其他
        'bin', 'dat', 'db', 'sqlite', 'sqlite3', 'mdb', 'accdb'
    }
    return file_extension in common_extensions

def calculate_file_hash(file_path):
    """计算文件的SHA256哈希值，用于去重"""
    sha256_hash = hashlib.sha256()
    try:
        with open(file_path, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()
    except Exception as e:
        print(f"计算文件哈希出错: {e}")
        return None

# ========== 第六步：定义API接口 ==========
# 这些是应用程序提供的功能接口

@app.route('/api/health', methods=['GET'])
def health_check():
    """健康检查接口 - 测试服务器是否正常"""
    return jsonify({
        'status': 'healthy',
        'timestamp': datetime.utcnow().isoformat(),
        'service': 'Personal Cloud Disk API',
        'version': '1.0.0',
        'database_path': app.config['SQLALCHEMY_DATABASE_URI'],
        'upload_path': app.config['UPLOAD_FOLDER']
    }), 200

@app.route('/api/network/info', methods=['GET'])
def network_info():
    """网络环境检测接口 - 检测服务器网络配置"""
    try:
        # 获取所有本地IP地址
        local_ips = []
        try:
            hostname = socket.gethostname()
            local_ips = socket.gethostbyname_ex(hostname)[2]
            # 过滤掉127.0.0.1和IPv6地址
            local_ips = [ip for ip in local_ips if not ip.startswith('127.') and ':' not in ip]
        except Exception as e:
            print(f"[网络检测] 获取本地IP失败: {e}")
        
        # 尝试获取公网IP
        public_ip = None
        try:
            # 使用多个服务尝试获取公网IP
            ip_services = [
                'https://api.ipify.org?format=json',
                'https://ifconfig.me/ip',
                'https://icanhazip.com'
            ]
            for service in ip_services:
                try:
                    with urllib.request.urlopen(service, timeout=3) as response:
                        result = response.read().decode('utf-8').strip()
                        # 处理JSON格式
                        if result.startswith('{'):
                            import json
                            result = json.loads(result).get('ip', result)
                        public_ip = result
                        break
                except Exception:
                    continue
        except Exception as e:
            print(f"[网络检测] 获取公网IP失败: {e}")
        
        # 判断网络类型
        network_type = "unknown"
        is_internal = False
        
        if local_ips:
            # 检查是否是内网IP
            for ip in local_ips:
                parts = ip.split('.')
                if len(parts) == 4:
                    first_octet = int(parts[0])
                    second_octet = int(parts[1])
                    
                    # 内网IP段判断
                    if (first_octet == 10) or \
                       (first_octet == 172 and 16 <= second_octet <= 31) or \
                       (first_octet == 192 and second_octet == 168):
                        is_internal = True
                        network_type = "internal"
                        break
            
            if not is_internal:
                network_type = "public"
        
        # 判断是否是校园网（通常使用10.x.x.x或特定IP段）
        is_campus_network = False
        if local_ips:
            for ip in local_ips:
                if ip.startswith('10.') or ip.startswith('172.') or ip.startswith('192.168.'):
                    is_campus_network = True
                    break
        
        # 生成配置建议
        suggestions = []
        if is_internal or is_campus_network:
            suggestions.append({
                'type': 'tunnel',
                'title': '使用内网穿透服务',
                'description': '推荐使用ngrok、frp或花生壳等内网穿透服务',
                'steps': [
                    '1. 下载并安装ngrok: https://ngrok.com/download',
                    '2. 注册账号并获取authtoken',
                    '3. 运行: ngrok authtoken YOUR_TOKEN',
                    '4. 运行: ngrok http 5000',
                    '5. 复制生成的公网URL（如: https://abc123.ngrok.io）',
                    '6. 在应用中输入: https://abc123.ngrok.io/api'
                ]
            })
            suggestions.append({
                'type': 'port_forward',
                'title': '配置路由器端口转发',
                'description': '如果路由器有管理权限，可以配置端口转发',
                'steps': [
                    '1. 登录路由器管理界面',
                    '2. 找到"端口转发"或"虚拟服务器"设置',
                    '3. 添加规则: 外网端口5000 -> 内网IP:5000',
                    '4. 使用路由器公网IP访问'
                ]
            })
        else:
            suggestions.append({
                'type': 'direct',
                'title': '直接使用公网IP',
                'description': '服务器已有公网IP，可直接访问',
                'steps': [
                    f'1. 确保防火墙开放5000端口',
                    f'2. 在应用中输入: http://{public_ip}:5000/api'
                ]
            })
        
        return jsonify({
            'success': True,
            'message': None,
            'data': {
                'local_ips': local_ips,
                'public_ip': public_ip,
                'network_type': network_type,
                'is_internal': is_internal,
                'is_campus_network': is_campus_network,
                'suggestions': suggestions,
                'timestamp': datetime.utcnow().isoformat()
            },
            'error': None
        }), 200
    except Exception as e:
        import traceback
        error_trace = traceback.format_exc()
        print(f"[错误] 网络检测错误: {str(e)}")
        print(f"[错误] 错误堆栈:\n{error_trace}")
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'网络检测失败: {str(e)}'
        }), 500

@app.route('/api/register', methods=['POST'])
def register():
    """
    用户注册接口
    请求示例: {"username": "user1", "email": "user1@example.com", "password": "password123"}
    """
    try:
        # 获取请求数据
        data = request.get_json()
        
        # 验证数据
        if not data or not data.get('username') or not data.get('email') or not data.get('password'):
            return jsonify({'error': '缺少必要信息'}), 400
        
        username = data.get('username', '').strip()
        email = data.get('email', '').strip()
        password = data.get('password', '')
        
        # 检查用户名是否已存在
        if User.query.filter_by(username=username).first():
            return jsonify({'error': '用户名已存在'}), 409
        
        # 检查邮箱是否已注册
        if User.query.filter_by(email=email).first():
            return jsonify({'error': '邮箱已注册'}), 409
        
        # 创建新用户
        user = User(username=username, email=email)
        user.set_password(password)
        
        db.session.add(user)
        db.session.flush()  # 获取用户ID
        
        # 创建用户文件夹
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user.id}")
        os.makedirs(user_folder_path, exist_ok=True)
        print(f"已创建用户文件夹: {user_folder_path}")
        
        db.session.commit()
        
        return jsonify({
            'message': '注册成功',
            'user': user.to_dict()
        }), 201
        
    except Exception as e:
        db.session.rollback()
        return jsonify({'error': f'服务器内部错误: {str(e)}'}), 500

@app.route('/api/login', methods=['POST'])
def login():
    """
    用户登录接口
    请求示例: {"username": "user1", "password": "password123"}
    """
    try:
        data = request.get_json()
        
        if not data or not data.get('username') or not data.get('password'):
            return jsonify({'error': '需要用户名和密码'}), 400
        
        username = data.get('username', '').strip()
        password = data.get('password', '')
        
        # 查找用户
        user = User.query.filter_by(username=username).first()
        
        if not user or not user.check_password(password):
            return jsonify({'error': '用户名或密码错误'}), 401
        
        # 确保用户文件夹存在
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user.id}")
        os.makedirs(user_folder_path, exist_ok=True)
        print(f"已确保用户文件夹存在: {user_folder_path}")
        
        # 创建JWT令牌 - identity必须是字符串
        user_identity = json.dumps({'id': user.id, 'username': user.username})
        access_token = create_access_token(identity=user_identity)
        
        return jsonify({
            'message': '登录成功',
            'access_token': access_token,
            'user': user.to_dict()
        }), 200
        
    except Exception as e:
        return jsonify({'error': f'登录失败: {str(e)}'}), 500

@app.route('/api/files', methods=['GET'])
@jwt_required()
def get_files():
    """获取用户的文件列表 - 需要登录才能访问，支持按folder_id过滤"""
    try:
        # 调试日志
        auth_header = request.headers.get('Authorization', '')
        print(f"收到文件列表请求，Authorization头: {auth_header[:50] if auth_header else 'None'}...")
        
        current_user = get_current_user()  # 获取当前登录用户
        print(f"当前用户: {current_user}")
        user_id = current_user['id']
        
        # 获取folder_id参数（可选）
        folder_id = request.args.get('folder_id', type=int)
        print(f"请求的folder_id: {folder_id}")
        
        # 获取用户的文件（如果folder_id为None，返回根目录文件；否则返回指定文件夹内的文件）
        try:
            query = File.query.filter_by(owner_id=user_id)
            if folder_id is None:
                # 根目录：folder_id为None的文件（使用is_来检查NULL）
                query = query.filter(File.folder_id.is_(None))
                print(f"[查询] 查询根目录文件 (folder_id IS NULL)")
            else:
                # 指定文件夹：folder_id匹配的文件
                query = query.filter_by(folder_id=folder_id)
                print(f"[查询] 查询文件夹 {folder_id} 内的文件")
            
            files = query.order_by(File.upload_date.desc()).all()
            print(f"[结果] 查询到 {len(files)} 个文件 (用户ID: {user_id}, folder_id: {folder_id})")
            
            # 调试：打印前几个文件的folder_id
            for i, f in enumerate(files[:5]):
                print(f"[调试] 文件[{i}]: id={f.id}, filename={f.original_filename}, folder_id={f.folder_id}")
        except Exception as db_error:
            print(f"[错误] 数据库查询错误: {str(db_error)}")
            import traceback
            print(traceback.format_exc())
            raise
        
        # 转换为字典列表
        file_list = []
        for file in files:
            try:
                file_dict = file.to_dict()
                file_list.append(file_dict)
            except Exception as dict_error:
                print(f"[错误] 转换文件字典错误 (文件ID: {file.id}): {str(dict_error)}")
                import traceback
                print(traceback.format_exc())
                # 跳过有问题的文件，继续处理其他文件
                continue
        
        return jsonify({
            'success': True,
            'message': '获取文件列表成功',
            'data': {
            'files': file_list,
            'count': len(file_list)
            },
            'error': None
        }), 200
        
    except Exception as e:
        import traceback
        error_trace = traceback.format_exc()
        print(f"[错误] 获取文件列表错误: {str(e)}")
        print(f"[错误] 错误堆栈:\n{error_trace}")
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'获取文件列表失败: {str(e)}'
        }), 500

@app.route('/api/upload', methods=['POST'])
@jwt_required()
def upload_file():
    """上传文件 - 需要登录才能访问"""
    try:
        # 调试日志
        auth_header = request.headers.get('Authorization', '')
        print(f"收到上传请求，Authorization头: {auth_header[:50] if auth_header else 'None'}...")
        print(f"Content-Type: {request.content_type}")
        print(f"请求文件: {list(request.files.keys())}")
        
        current_user = get_current_user()
        print(f"当前用户: {current_user}")
        
        if 'file' not in request.files:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '没有选择文件'
            }), 400
        
        file = request.files['file']
        
        # 打印上传的文件名信息（用于调试）
        print(f"[上传] 接收到的文件名: {file.filename}")
        print(f"[上传] Content-Type: {request.content_type}")
        
        if file.filename == '':
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '没有选择文件'
            }), 400
        
        if not allowed_file(file.filename):
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件类型不允许'
            }), 400
        
        # 获取当前用户（已在上面获取）
        user_id = current_user['id']
        
        # 获取folder_id参数（可选，从form数据中获取）
        folder_id_str = request.form.get('folder_id', '')
        print(f"上传文件的folder_id (原始): '{folder_id_str}'")
        
        # 处理folder_id：如果是空字符串或None，设置为None；否则转换为int
        if folder_id_str and folder_id_str.strip():
            try:
                folder_id = int(folder_id_str)
                print(f"上传文件的folder_id (转换后): {folder_id}")
            except (ValueError, TypeError):
                folder_id = None
                print(f"上传文件的folder_id (转换失败，设为None)")
        else:
            folder_id = None
            print(f"上传文件的folder_id (空值，设为None)")
        
        # 如果提供了folder_id，验证文件夹是否存在且属于当前用户
        if folder_id is not None:
            folder = Folder.query.filter_by(id=folder_id, owner_id=user_id).first()
            if not folder:
                return jsonify({
                    'success': False,
                    'message': None,
                    'data': None,
                    'error': '指定的文件夹不存在或无权访问'
                }), 404
        
        # 优先从form数据中获取original_filename（如果客户端单独传递了）
        original_filename = request.form.get('original_filename', '').strip()
        print(f"[上传] 从form获取的original_filename: '{original_filename}'")
        
        # 如果form中没有，尝试从multipart文件名获取
        if not original_filename:
            raw_filename = file.filename
            if not raw_filename or raw_filename == '':
                # 尝试从Content-Disposition头获取文件名
                content_disposition = request.headers.get('Content-Disposition', '')
                if content_disposition:
                    import re
                    # 支持RFC 5987格式的文件名（支持UTF-8编码）
                    filename_match = re.search(r"filename\*=UTF-8''([^;]+)", content_disposition)
                    if filename_match:
                        import urllib.parse
                        raw_filename = urllib.parse.unquote(filename_match.group(1))
                    else:
                        # 回退到标准格式
                        filename_match = re.search(r'filename[^;=\n]*=(([\'"]).*?\2|[^;\n]*)', content_disposition)
                        if filename_match:
                            raw_filename = filename_match.group(1).strip('"\'')
            
            # 处理文件名：保留原始文件名，只清理危险字符
            # secure_filename会移除中文字符，所以我们需要先保存原始文件名
            original_filename = raw_filename if raw_filename else "unknown_file"
        
        # URL解码文件名（处理可能的URL编码）
        if original_filename and '%' in original_filename:
            import urllib.parse
            try:
                original_filename = urllib.parse.unquote(original_filename, encoding='utf-8')
            except:
                pass
        
        # 提取文件扩展名（从原始文件名提取，而不是从secure_filename后的文件名）
        file_extension = ''
        if original_filename and '.' in original_filename:
            try:
                file_extension = original_filename.rsplit('.', 1)[1].lower().strip()
                # 清理扩展名（移除可能的特殊字符）
                file_extension = ''.join(c for c in file_extension if c.isalnum() or c in ['-', '_'])
            except:
                file_extension = ''
        
        # 如果secure_filename移除了所有字符（比如中文文件名），使用原始文件名
        safe_filename = secure_filename(original_filename)
        if not safe_filename or safe_filename == '':
            # 如果secure_filename处理失败，使用原始文件名但清理危险字符
            import re
            safe_filename = re.sub(r'[^\w\s\-_\.]', '_', original_filename)
        
        print(f"[上传] 原始文件名: {original_filename}, 安全文件名: {safe_filename}, 扩展名: {file_extension}")
        
        # 生成唯一文件名（防止重名）
        unique_filename = f"{uuid.uuid4().hex}.{file_extension}" if file_extension else uuid.uuid4().hex
        
        # 确定保存路径：如果有folder_id，保存到对应文件夹；否则保存到根目录
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        if folder_id is not None:
            # 获取文件夹信息
            folder = Folder.query.filter_by(id=folder_id, owner_id=user_id).first()
            if folder:
                # 构建文件夹的完整路径（递归查找所有父文件夹）
                folder_path_parts = [folder.folder_name]
                current_parent = folder
                while current_parent and current_parent.parent_folder_id:
                    parent_folder = Folder.query.filter_by(id=current_parent.parent_folder_id, owner_id=user_id).first()
                    if parent_folder:
                        folder_path_parts.insert(0, parent_folder.folder_name)
                        current_parent = parent_folder
                    else:
                        break
                
                # 构建完整路径
                folder_path = os.path.join(user_folder_path, *folder_path_parts)
                os.makedirs(folder_path, exist_ok=True)
                file_path = os.path.join(folder_path, unique_filename)
                print(f"[上传] 文件保存到文件夹: {folder_path}")
            else:
                # 文件夹不存在，保存到根目录
                os.makedirs(user_folder_path, exist_ok=True)
                file_path = os.path.join(user_folder_path, unique_filename)
                print(f"[上传] 文件夹不存在，保存到根目录: {user_folder_path}")
        else:
            # 保存到根目录
            os.makedirs(user_folder_path, exist_ok=True)
            file_path = os.path.join(user_folder_path, unique_filename)
            print(f"[上传] 文件保存到根目录: {user_folder_path}")
        
        # 保存文件
        file.save(file_path)
        file_size = os.path.getsize(file_path)
        file_hash = calculate_file_hash(file_path)
        
        if not file_hash:
            os.remove(file_path)
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件处理失败'
            }), 500
        
        # 检查是否已存在相同文件（在同一文件夹内）
        existing_file = File.query.filter_by(
            file_hash=file_hash, 
            owner_id=user_id,
            folder_id=folder_id
        ).first()
        if existing_file:
            os.remove(file_path)  # 删除重复文件
            return jsonify({
                'success': True,
                'message': '文件已存在',
                'data': existing_file.to_dict(),
                'error': None
            }), 200
        
        # 创建文件记录
        new_file = File(
            filename=unique_filename,
            original_filename=original_filename,
            file_size=file_size,
            file_hash=file_hash,
            file_type=file_extension,
            owner_id=user_id,
            folder_id=folder_id  # 设置文件夹ID
        )
        
        db.session.add(new_file)
        db.session.commit()
        
        return jsonify({
            'success': True,
            'message': '文件上传成功',
            'data': new_file.to_dict(),
            'error': None
        }), 201
        
    except Exception as e:
        db.session.rollback()
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'上传失败: {str(e)}'
        }), 500

@app.route('/api/files/<int:file_id>/share', methods=['POST'])
@jwt_required()
def create_file_share(file_id):
    """生成文件分享链接"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 验证文件存在且属于当前用户
        file = File.query.filter_by(id=file_id, owner_id=user_id).first()
        
        if not file:
            return jsonify({
                'success': False,
                'error': '文件不存在或无权访问'
            }), 404
        
        # 生成分享令牌
        share_token = file.generate_share_token(days=7)
        
        db.session.commit()
        
        return jsonify({
            'success': True,
            'message': '分享链接创建成功',
            'data': {
            'share_token': share_token,
            'share_url': f'http://localhost:5000/api/share/{share_token}',
            'expiry': file.share_expiry.isoformat() if file.share_expiry else None
            },
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        return jsonify({
            'success': False,
            'error': f'分享失败: {str(e)}'
        }), 500

@app.route('/api/folders/<int:folder_id>/share', methods=['POST'])
@jwt_required()
def create_folder_share(folder_id):
    """生成文件夹分享链接"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 验证文件夹存在且属于当前用户
        folder = Folder.query.filter_by(id=folder_id, owner_id=user_id).first()
        
        if not folder:
            return jsonify({
                'success': False,
                'error': '文件夹不存在或无权访问'
            }), 404
        
        # 生成分享令牌
        share_token = folder.generate_share_token(days=7)
        
        db.session.commit()
        
        return jsonify({
            'success': True,
            'message': '分享链接创建成功',
            'data': {
                'share_token': share_token,
                'share_url': f'http://localhost:5000/api/share/{share_token}',
                'expiry': folder.share_expiry.isoformat() if folder.share_expiry else None
            },
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        return jsonify({
            'success': False,
            'error': f'分享失败: {str(e)}'
        }), 500

@app.route('/api/share/<share_token>/save', methods=['POST'])
@jwt_required()
def save_shared_content(share_token):
    """保存分享的内容到当前用户的目录，支持保存选中的文件"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        data = request.get_json() or {}
        target_folder_id = data.get('folder_id')  # 目标文件夹ID，None表示根目录
        selected_file_ids = data.get('file_ids', [])  # 选中的文件ID列表
        selected_folder_ids = data.get('folder_ids', [])  # 选中的文件夹ID列表
        
        # 查找分享的文件或文件夹
        file = File.query.filter_by(share_token=share_token).first()
        folder = None
        if not file:
            folder = Folder.query.filter_by(share_token=share_token).first()
        
        if not file and not folder:
            return jsonify({
                'success': False,
                'error': '分享链接无效'
            }), 404
        
        # 检查分享是否过期
        if file and file.share_expiry and file.share_expiry < datetime.utcnow():
            return jsonify({
                'success': False,
                'error': '分享链接已过期'
            }), 410
        if folder and folder.share_expiry and folder.share_expiry < datetime.utcnow():
            return jsonify({
                'success': False,
                'error': '分享链接已过期'
            }), 410
        
        # 如果提供了选中的文件/文件夹ID列表，只保存选中的项
        if selected_file_ids or selected_folder_ids:
            saved_count = 0
            
            # 确定目标路径
            target_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
            if target_folder_id:
                target_folder = Folder.query.filter_by(id=target_folder_id, owner_id=user_id).first()
                if not target_folder:
                    return jsonify({
                        'success': False,
                        'error': '目标文件夹不存在'
                    }), 404
                target_path = os.path.join(target_path, target_folder.folder_name)
            
            os.makedirs(target_path, exist_ok=True)
            
            # 保存选中的文件
            for file_id in selected_file_ids:
                source_file = File.query.filter_by(id=file_id).first()
                if not source_file:
                    continue
                
                # 检查文件是否属于分享的内容
                # 如果是单个文件分享，只允许保存这个文件
                if file and source_file.id != file.id:
                    continue
                # 如果是文件夹分享，检查文件是否属于分享的文件夹
                if folder and source_file.folder_id != folder.id:
                    continue
                
                # 构建源文件路径
                source_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{source_file.owner_id}")
                if source_file.folder_id:
                    source_folder_obj = Folder.query.filter_by(id=source_file.folder_id).first()
                    if source_folder_obj:
                        source_path = os.path.join(source_path, source_folder_obj.folder_name)
                
                source_file_path = os.path.join(source_path, source_file.filename)
                
                if os.path.exists(source_file_path):
                    target_file_path = os.path.join(target_path, source_file.filename)
                    shutil.copy2(source_file_path, target_file_path)
                    
                    # 确保文件类型正确（如果为空，从文件名中提取）
                    file_type = source_file.file_type
                    if not file_type and source_file.original_filename:
                        if '.' in source_file.original_filename:
                            file_type = source_file.original_filename.rsplit('.', 1)[1].lower()
                    
                    # 创建文件记录
                    new_file = File(
                        filename=source_file.filename,
                        original_filename=source_file.original_filename,
                        file_size=source_file.file_size,
                        file_hash=source_file.file_hash,
                        file_type=file_type,
                        owner_id=user_id,
                        folder_id=target_folder_id
                    )
                    db.session.add(new_file)
                    saved_count += 1
            
            # 保存选中的文件夹（递归保存文件夹内的所有文件）
            for folder_id in selected_folder_ids:
                source_folder_obj = Folder.query.filter_by(id=folder_id).first()
                if not source_folder_obj:
                    continue
                
                # 检查文件夹是否属于分享的文件夹（如果是文件夹分享）
                if folder and source_folder_obj.parent_folder_id != folder.id:
                    continue
                
                # 创建目标文件夹
                new_folder = Folder(
                    folder_name=source_folder_obj.folder_name,
                    owner_id=user_id,
                    parent_folder_id=target_folder_id
                )
                db.session.add(new_folder)
                db.session.flush()
                
                target_folder_path = os.path.join(target_path, new_folder.folder_name)
                os.makedirs(target_folder_path, exist_ok=True)
                
                # 复制文件夹内的所有文件
                source_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{source_folder_obj.owner_id}", source_folder_obj.folder_name)
                files_in_folder = File.query.filter_by(folder_id=source_folder_obj.id, owner_id=source_folder_obj.owner_id).all()
                
                for source_file in files_in_folder:
                    source_file_path = os.path.join(source_folder_path, source_file.filename)
                    if os.path.exists(source_file_path):
                        target_file_path = os.path.join(target_folder_path, source_file.filename)
                        shutil.copy2(source_file_path, target_file_path)
                        
                        # 确保文件类型正确（如果为空，从文件名中提取）
                        file_type = source_file.file_type
                        if not file_type and source_file.original_filename:
                            if '.' in source_file.original_filename:
                                file_type = source_file.original_filename.rsplit('.', 1)[1].lower()
                        
                        new_file = File(
                            filename=source_file.filename,
                            original_filename=source_file.original_filename,
                            file_size=source_file.file_size,
                            file_hash=source_file.file_hash,
                            file_type=file_type,
                            owner_id=user_id,
                            folder_id=new_folder.id
                        )
                        db.session.add(new_file)
                        saved_count += 1
            
            db.session.commit()
            
            return jsonify({
                'success': True,
                'message': f'已保存 {saved_count} 个文件',
                'data': {
                    'saved_count': saved_count
                },
                'error': None
            }), 200
        
        # 如果没有提供选中的文件列表，保存所有内容（原有逻辑）
        if file:
            # 保存分享的文件
            source_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{file.owner_id}")
            if file.folder_id:
                source_folder = Folder.query.filter_by(id=file.folder_id).first()
                if source_folder:
                    source_path = os.path.join(source_path, source_folder.folder_name)
            
            source_file_path = os.path.join(source_path, file.filename)
            
            if not os.path.exists(source_file_path):
                return jsonify({
                    'success': False,
                    'error': '源文件不存在'
                }), 404
            
            # 确定目标路径
            target_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
            if target_folder_id:
                target_folder = Folder.query.filter_by(id=target_folder_id, owner_id=user_id).first()
                if not target_folder:
                    return jsonify({
                        'success': False,
                        'error': '目标文件夹不存在'
                    }), 404
                target_path = os.path.join(target_path, target_folder.folder_name)
            
            os.makedirs(target_path, exist_ok=True)
            target_file_path = os.path.join(target_path, file.filename)
            
            # 复制文件
            shutil.copy2(source_file_path, target_file_path)
            
            # 确保文件类型正确（如果为空，从文件名中提取）
            file_type = file.file_type
            if not file_type and file.original_filename:
                if '.' in file.original_filename:
                    file_type = file.original_filename.rsplit('.', 1)[1].lower()
            
            # 创建文件记录
            new_file = File(
                filename=file.filename,
                original_filename=file.original_filename,
                file_size=file.file_size,
                file_hash=file.file_hash,
                file_type=file_type,
                owner_id=user_id,
                folder_id=target_folder_id
            )
            
            db.session.add(new_file)
            db.session.commit()
            
            return jsonify({
                'success': True,
                'message': '文件保存成功',
                'data': new_file.to_dict(),
                'error': None
            }), 200
        
        else:  # folder
            # 保存分享的文件夹（递归复制所有文件和子文件夹）
            # 这里简化处理，只复制文件夹内的文件
            source_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{folder.owner_id}", folder.folder_name)
            
            if not os.path.exists(source_path):
                return jsonify({
                    'success': False,
                    'error': '源文件夹不存在'
                }), 404
            
            # 创建目标文件夹
            target_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
            if target_folder_id:
                target_folder = Folder.query.filter_by(id=target_folder_id, owner_id=user_id).first()
                if not target_folder:
                    return jsonify({
                        'success': False,
                        'error': '目标文件夹不存在'
                    }), 404
                target_path = os.path.join(target_path, target_folder.folder_name)
            
            # 创建新文件夹
            new_folder = Folder(
                folder_name=folder.folder_name,
                owner_id=user_id,
                parent_folder_id=target_folder_id
            )
            db.session.add(new_folder)
            db.session.flush()
            
            target_folder_path = os.path.join(target_path, new_folder.folder_name)
            os.makedirs(target_folder_path, exist_ok=True)
            
            # 复制文件夹内的所有文件
            files_in_folder = File.query.filter_by(folder_id=folder.id, owner_id=folder.owner_id).all()
            saved_count = 0
            
            for source_file in files_in_folder:
                source_file_path = os.path.join(source_path, source_file.filename)
                if os.path.exists(source_file_path):
                    target_file_path = os.path.join(target_folder_path, source_file.filename)
                    shutil.copy2(source_file_path, target_file_path)
                    
                    new_file = File(
                        filename=source_file.filename,
                        original_filename=source_file.original_filename,
                        file_size=source_file.file_size,
                        file_hash=source_file.file_hash,
                        file_type=source_file.file_type,
                        owner_id=user_id,
                        folder_id=new_folder.id
                    )
                    db.session.add(new_file)
                    saved_count += 1
            
            db.session.commit()
            
            return jsonify({
                'success': True,
                'message': f'文件夹保存成功，已保存 {saved_count} 个文件',
                'data': {
                    'folder': new_folder.to_dict(),
                    'saved_files_count': saved_count
                },
                'error': None
            }), 200
        
    except Exception as e:
        db.session.rollback()
        import traceback
        error_trace = traceback.format_exc()
        print(f"[错误] 保存分享内容错误: {str(e)}")
        print(f"[错误] 错误堆栈:\n{error_trace}")
        return jsonify({
            'success': False,
            'error': f'保存失败: {str(e)}'
        }), 500

@app.route('/api/share/<share_token>', methods=['GET'])
def get_shared_content(share_token):
    """通过分享令牌获取分享内容（文件或文件夹）"""
    try:
        # 先尝试查找文件
        file = File.query.filter_by(share_token=share_token).first()
        if file:
            # 检查分享是否过期
            if file.share_expiry and file.share_expiry < datetime.utcnow():
                return jsonify({'error': '分享链接已过期'}), 410
            
            # 获取文件所有者信息
            owner = User.query.get(file.owner_id)
            owner_name = owner.username if owner else '未知用户'
            
            return jsonify({
                'success': True,
                'type': 'file',
                'data': {
                    'id': file.id,
                    'filename': file.original_filename,
                    'file_size': file.file_size,
                    'file_type': file.file_type,
                    'upload_date': file.upload_date.isoformat() if file.upload_date else None,
                    'owner': owner_name,
                    'files': [file.to_dict()],
                    'folders': []
                },
                'error': None
            }), 200
        
        # 尝试查找文件夹
        folder = Folder.query.filter_by(share_token=share_token).first()
        if folder:
            # 检查分享是否过期
            if folder.share_expiry and folder.share_expiry < datetime.utcnow():
                return jsonify({'error': '分享链接已过期'}), 410
            
            # 递归获取文件夹内的所有文件和子文件夹
            def get_all_files_and_folders(folder_id):
                """递归获取文件夹及其所有子文件夹中的文件和文件夹"""
                all_files = []
                all_folders = []
                
                # 获取当前文件夹的直接文件和子文件夹
                files = File.query.filter_by(folder_id=folder_id, owner_id=folder.owner_id).all()
                sub_folders = Folder.query.filter_by(parent_folder_id=folder_id, owner_id=folder.owner_id).all()
                
                all_files.extend(files)
                all_folders.extend(sub_folders)
                
                # 递归获取所有子文件夹的内容
                for sub_folder in sub_folders:
                    sub_files, sub_folders_recursive = get_all_files_and_folders(sub_folder.id)
                    all_files.extend(sub_files)
                    all_folders.extend(sub_folders_recursive)
                
                return all_files, all_folders
            
            all_files, all_folders = get_all_files_and_folders(folder.id)
            
            # 将根文件夹本身也添加到folders列表中（放在第一位，用于前端识别）
            root_folder_dict = folder.to_dict()
            folders_list = [root_folder_dict]  # 根文件夹放在第一位
            
            # 添加所有子文件夹（排除根文件夹本身，避免重复）
            for f in all_folders:
                if f.id != folder.id:
                    folders_list.append(f.to_dict())
            
            # 获取文件夹所有者信息
            owner = User.query.get(folder.owner_id)
            owner_name = owner.username if owner else '未知用户'
            
            return jsonify({
                'success': True,
                'type': 'folder',
                'data': {
                    'id': folder.id,
                    'folder_name': folder.folder_name,
                    'created_date': folder.created_date.isoformat() if folder.created_date else None,
                    'owner': owner_name,
                    'files': [f.to_dict() for f in all_files],
                    'folders': folders_list  # 包含根文件夹和所有子文件夹
                },
                'error': None
            }), 200
        
        return jsonify({
            'success': False,
            'error': '分享链接无效'
        }), 404
        
    except Exception as e:
        import traceback
        error_trace = traceback.format_exc()
        print(f"[错误] 获取分享信息错误: {str(e)}")
        print(f"[错误] 错误堆栈:\n{error_trace}")
        return jsonify({
            'success': False,
            'error': f'获取分享信息失败: {str(e)}'
        }), 500

@app.route('/api/files/<int:file_id>/download', methods=['GET'])
@jwt_required()
def download_file(file_id):
    """下载自己的文件"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        file = File.query.filter_by(id=file_id, owner_id=user_id).first()
        
        if not file:
            return jsonify({'error': '文件不存在或无权访问'}), 404
        
        # 从用户文件夹中读取文件
        # 需要检查文件是否在根目录或子文件夹中
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        
        # 如果文件在文件夹中，需要指定文件夹路径
        if file.folder_id is not None:
            folder = Folder.query.filter_by(id=file.folder_id, owner_id=user_id).first()
            if folder:
                # 构建文件夹的完整路径（递归查找所有父文件夹）
                folder_path_parts = [folder.folder_name]
                current_parent = folder
                while current_parent and current_parent.parent_folder_id:
                    parent_folder = Folder.query.filter_by(id=current_parent.parent_folder_id, owner_id=user_id).first()
                    if parent_folder:
                        folder_path_parts.insert(0, parent_folder.folder_name)
                        current_parent = parent_folder
                    else:
                        break
                
                # 构建完整路径
                folder_path = os.path.join(user_folder_path, *folder_path_parts)
                file_full_path = os.path.join(folder_path, file.filename)
                
                if os.path.exists(file_full_path):
                    return send_from_directory(
                        folder_path,
                        file.filename,
                        as_attachment=True,
                        download_name=file.original_filename
                    )
                else:
                    print(f"[下载] 文件不存在: {file_full_path}")
                    return jsonify({'error': f'下载失败: 文件不存在于路径 {file_full_path}'}), 404
        
        # 默认从根目录读取
        return send_from_directory(
            user_folder_path,
            file.filename,
            as_attachment=True,
            download_name=file.original_filename
        )
        
    except Exception as e:
        return jsonify({'error': f'下载失败: {str(e)}'}), 500

@app.route('/api/share/<share_token>/download', methods=['GET'])
def download_shared_file(share_token):
    """下载分享的文件"""
    try:
        file = File.query.filter_by(share_token=share_token).first()
        
        if not file:
            return jsonify({'error': '分享链接无效'}), 404
        
        if file.share_expiry and file.share_expiry < datetime.utcnow():
            return jsonify({'error': '分享链接已过期'}), 410
        
        # 从用户文件夹中读取文件
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{file.owner_id}")
        return send_from_directory(
            user_folder_path,
            file.filename,
            as_attachment=True,
            download_name=file.original_filename
        )
        
    except Exception as e:
        return jsonify({'error': f'下载失败: {str(e)}'}), 500

@app.route('/api/files/<int:file_id>', methods=['DELETE'])
@jwt_required()
def delete_file(file_id):
    """删除文件"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 查找文件
        file = File.query.filter_by(id=file_id, owner_id=user_id).first()
        
        if not file:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件不存在或无权访问'
            }), 404
        
        # 删除物理文件
        # 需要检查文件是否在根目录或子文件夹中
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        
        # 先尝试在根目录查找
        file_path = os.path.join(user_folder_path, file.filename)
        
        # 如果不在根目录，可能在子文件夹中
        if not os.path.exists(file_path) and file.folder_id is not None:
            folder = Folder.query.filter_by(id=file.folder_id, owner_id=user_id).first()
            if folder:
                folder_path = os.path.join(user_folder_path, folder.folder_name)
                file_path = os.path.join(folder_path, file.filename)
        
        if os.path.exists(file_path):
            try:
                os.remove(file_path)
                print(f"[删除] 已删除物理文件: {file_path}")
            except Exception as e:
                print(f"[警告] 删除物理文件失败: {file_path}, 错误: {str(e)}")
        else:
            print(f"[警告] 物理文件不存在: {file_path}，可能已被外部删除")
        
        # 删除数据库记录
        db.session.delete(file)
        db.session.commit()
        
        print(f"[删除] 已删除文件记录: ID={file_id}, 文件名={file.original_filename}")
        
        return jsonify({
            'success': True,
            'message': '文件删除成功',
            'data': None,
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        import traceback
        print(f"[错误] 删除文件失败: {str(e)}")
        print(traceback.format_exc())
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'删除失败: {str(e)}'
        }), 500

def build_folder_path(user_id, folder_id):
    """递归构建文件夹的完整路径"""
    folder = Folder.query.filter_by(id=folder_id, owner_id=user_id).first()
    if not folder:
        return None
    
    folder_path_parts = [folder.folder_name]
    current_parent = folder
    while current_parent and current_parent.parent_folder_id:
        parent_folder = Folder.query.filter_by(id=current_parent.parent_folder_id, owner_id=user_id).first()
        if parent_folder:
            folder_path_parts.insert(0, parent_folder.folder_name)
            current_parent = parent_folder
        else:
            break
    
    user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
    return os.path.join(user_folder_path, *folder_path_parts)

@app.route('/api/files/<int:file_id>/move', methods=['PUT'])
@jwt_required()
def move_file(file_id):
    """移动文件到指定文件夹"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 查找文件
        file = File.query.filter_by(id=file_id, owner_id=user_id).first()
        
        if not file:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件不存在或无权访问'
            }), 404
        
        # 获取目标文件夹ID
        data = request.get_json()
        target_folder_id = data.get('folder_id') if data else None
        
        # 如果指定了目标文件夹，验证文件夹存在且属于当前用户
        if target_folder_id is not None:
            target_folder = Folder.query.filter_by(id=target_folder_id, owner_id=user_id).first()
            if not target_folder:
                return jsonify({
                    'success': False,
                    'message': None,
                    'data': None,
                    'error': '目标文件夹不存在或无权访问'
                }), 404
        
        # 更新文件的folder_id
        old_folder_id = file.folder_id
        file.folder_id = target_folder_id
        
        # 移动物理文件（如果需要）
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        
        # 构建旧路径
        old_path = None
        if old_folder_id is not None:
            old_folder = Folder.query.filter_by(id=old_folder_id, owner_id=user_id).first()
            if old_folder:
                old_folder_path = build_folder_path(user_id, old_folder_id)
                old_path = os.path.join(old_folder_path, file.filename)
        else:
            old_path = os.path.join(user_folder_path, file.filename)
        
        # 构建新路径
        new_path = None
        if target_folder_id is not None:
            new_folder_path = build_folder_path(user_id, target_folder_id)
            new_path = os.path.join(new_folder_path, file.filename)
        else:
            new_path = os.path.join(user_folder_path, file.filename)
        
        # 如果路径不同，移动物理文件
        if old_path and new_path and old_path != new_path and os.path.exists(old_path):
            try:
                os.makedirs(os.path.dirname(new_path), exist_ok=True)
                shutil.move(old_path, new_path)
                print(f"[移动] 已移动物理文件: {old_path} -> {new_path}")
            except Exception as e:
                print(f"[警告] 移动物理文件失败: {str(e)}")
                # 即使物理文件移动失败，也更新数据库记录
        
        db.session.commit()
        
        print(f"[移动] 已移动文件: ID={file_id}, folder_id={old_folder_id} -> {target_folder_id}")
        
        return jsonify({
            'success': True,
            'message': '文件移动成功',
            'data': file.to_dict(),
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        import traceback
        print(f"[错误] 移动文件失败: {str(e)}")
        print(traceback.format_exc())
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'移动失败: {str(e)}'
        }), 500

@app.route('/api/files/<int:file_id>/rename', methods=['PUT'])
@jwt_required()
def rename_file(file_id):
    """重命名文件"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 查找文件
        file = File.query.filter_by(id=file_id, owner_id=user_id).first()
        
        if not file:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件不存在或无权访问'
            }), 404
        
        # 获取新文件名
        data = request.get_json()
        new_filename = data.get('filename') if data else None
        
        if not new_filename or not new_filename.strip():
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '新文件名不能为空'
            }), 400
        
        new_filename = new_filename.strip()
        
        # 检查是否与当前文件名相同
        if new_filename == file.original_filename:
            return jsonify({
                'success': True,
                'message': '文件名未改变',
                'data': file.to_dict(),
                'error': None
            }), 200
        
        # 检查新文件名是否已存在（在同一文件夹中）
        existing_file = File.query.filter_by(
            original_filename=new_filename,
            folder_id=file.folder_id,
            owner_id=user_id
        ).first()
        
        if existing_file and existing_file.id != file_id:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '该文件名已存在'
            }), 409
        
        # 重命名物理文件
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        
        old_file_path = None
        if file.folder_id is not None:
            folder_path = build_folder_path(user_id, file.folder_id)
            if folder_path:
                old_file_path = os.path.join(folder_path, file.filename)
            else:
                old_file_path = os.path.join(user_folder_path, file.filename)
        else:
            old_file_path = os.path.join(user_folder_path, file.filename)
        
        # 提取文件扩展名
        old_ext = os.path.splitext(file.filename)[1]
        new_ext = os.path.splitext(new_filename)[1]
        
        # 如果扩展名不同，使用新扩展名；否则保持原扩展名
        final_ext = new_ext if new_ext else old_ext
        new_stored_filename = secure_filename(new_filename)
        if not new_stored_filename.endswith(final_ext):
            new_stored_filename = os.path.splitext(new_stored_filename)[0] + final_ext
        
        # 构建新文件路径（在同一目录下）
        if file.folder_id is not None:
            folder_path = build_folder_path(user_id, file.folder_id)
            if folder_path:
                new_file_path = os.path.join(folder_path, new_stored_filename)
            else:
                new_file_path = os.path.join(user_folder_path, new_stored_filename)
        else:
            new_file_path = os.path.join(user_folder_path, new_stored_filename)
        
        if os.path.exists(old_file_path) and old_file_path != new_file_path:
            try:
                os.rename(old_file_path, new_file_path)
                print(f"[重命名] 已重命名物理文件: {old_file_path} -> {new_file_path}")
            except Exception as e:
                print(f"[警告] 重命名物理文件失败: {str(e)}")
        
        # 更新数据库记录
        file.original_filename = new_filename
        file.filename = new_stored_filename
        
        # 更新文件类型（如果扩展名改变）
        if final_ext:
            file.file_type = final_ext[1:].lower()  # 去掉点号并转为小写
        
        db.session.commit()
        
        print(f"[重命名] 已重命名文件: ID={file_id}, {file.original_filename} -> {new_filename}")
        
        return jsonify({
            'success': True,
            'message': '文件重命名成功',
            'data': file.to_dict(),
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        import traceback
        print(f"[错误] 重命名文件失败: {str(e)}")
        print(traceback.format_exc())
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'重命名失败: {str(e)}'
        }), 500

@app.route('/api/folders/<int:folder_id>/rename', methods=['PUT'])
@jwt_required()
def rename_folder(folder_id):
    """重命名文件夹"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 查找文件夹
        folder = Folder.query.filter_by(id=folder_id, owner_id=user_id).first()
        
        if not folder:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件夹不存在或无权访问'
            }), 404
        
        # 获取新文件夹名
        data = request.get_json()
        new_folder_name = data.get('folder_name') if data else None
        
        if not new_folder_name or not new_folder_name.strip():
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '新文件夹名不能为空'
            }), 400
        
        new_folder_name = new_folder_name.strip()
        
        # 检查是否与当前文件夹名相同
        if new_folder_name == folder.folder_name:
            return jsonify({
                'success': True,
                'message': '文件夹名未改变',
                'data': folder.to_dict(),
                'error': None
            }), 200
        
        # 检查新文件夹名是否已存在（在同一父文件夹中）
        existing_folder = Folder.query.filter_by(
            folder_name=new_folder_name,
            parent_folder_id=folder.parent_folder_id,
            owner_id=user_id
        ).first()
        
        if existing_folder and existing_folder.id != folder_id:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '该文件夹名已存在'
            }), 409
        
        # 重命名物理文件夹
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        
        old_folder_path = None
        if folder.parent_folder_id is not None:
            parent_folder_path = build_folder_path(user_id, folder.parent_folder_id)
            if parent_folder_path:
                old_folder_path = os.path.join(parent_folder_path, folder.folder_name)
            else:
                old_folder_path = os.path.join(user_folder_path, folder.folder_name)
        else:
            old_folder_path = os.path.join(user_folder_path, folder.folder_name)
        
        new_folder_path = os.path.join(os.path.dirname(old_folder_path), new_folder_name)
        
        if os.path.exists(old_folder_path) and old_folder_path != new_folder_path:
            try:
                os.rename(old_folder_path, new_folder_path)
                print(f"[重命名] 已重命名物理文件夹: {old_folder_path} -> {new_folder_path}")
            except Exception as e:
                print(f"[警告] 重命名物理文件夹失败: {str(e)}")
        
        # 更新数据库记录
        folder.folder_name = new_folder_name
        
        db.session.commit()
        
        print(f"[重命名] 已重命名文件夹: ID={folder_id}, {folder.folder_name} -> {new_folder_name}")
        
        return jsonify({
            'success': True,
            'message': '文件夹重命名成功',
            'data': folder.to_dict(),
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        import traceback
        print(f"[错误] 重命名文件夹失败: {str(e)}")
        print(traceback.format_exc())
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'重命名失败: {str(e)}'
        }), 500

@app.route('/api/profile', methods=['GET'])
@jwt_required()
def get_profile():
    """获取用户信息"""
    try:
        # 调试日志
        auth_header = request.headers.get('Authorization', '')
        print(f"收到profile请求，Authorization头: {auth_header[:50] if auth_header else 'None'}...")
        
        current_user = get_current_user()
        print(f"当前用户: {current_user}")
        user = User.query.get(current_user['id'])
        
        if not user:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '用户不存在'
            }), 404
        
        # 统计用户文件信息
        files = File.query.filter_by(owner_id=user.id).all()
        files_count = len(files)
        total_size = sum(file.file_size for file in files)
        
        return jsonify({
            'success': True,
            'message': '获取用户信息成功',
            'data': {
            'user': user.to_dict(),
            'stats': {
                'files_count': files_count,
                'total_size': total_size,
                'total_size_mb': round(total_size / (1024 * 1024), 2)
            }
            },
            'error': None
        }), 200
        
    except Exception as e:
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'获取个人信息失败: {str(e)}'
        }), 500

@app.route('/api/profile', methods=['PUT'])
@jwt_required()
def update_profile():
    """更新用户信息"""
    try:
        current_user = get_current_user()
        user = User.query.get(current_user['id'])
        
        if not user:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '用户不存在'
            }), 404
        
        data = request.get_json()
        if not data:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '请求数据为空'
            }), 400
        
        # 更新用户名
        if 'username' in data:
            new_username = data['username'].strip()
            if new_username and new_username != user.username:
                # 检查用户名是否已被使用
                existing_user = User.query.filter_by(username=new_username).first()
                if existing_user and existing_user.id != user.id:
                    return jsonify({
                        'success': False,
                        'message': None,
                        'data': None,
                        'error': '用户名已被使用'
                    }), 400
                user.username = new_username
        
        # 更新邮箱
        if 'email' in data:
            new_email = data['email'].strip()
            if new_email and new_email != user.email:
                # 检查邮箱是否已被使用
                existing_user = User.query.filter_by(email=new_email).first()
                if existing_user and existing_user.id != user.id:
                    return jsonify({
                        'success': False,
                        'message': None,
                        'data': None,
                        'error': '邮箱已被使用'
                    }), 400
                user.email = new_email
        
        db.session.commit()
        
        return jsonify({
            'success': True,
            'message': '更新用户信息成功',
            'data': user.to_dict(),
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'更新用户信息失败: {str(e)}'
        }), 500

@app.route('/api/profile/avatar', methods=['POST'])
@jwt_required()
def upload_avatar():
    """上传用户头像"""
    try:
        current_user = get_current_user()
        user = User.query.get(current_user['id'])
        
        if not user:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '用户不存在'
            }), 404
        
        if 'avatar' not in request.files:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '未找到头像文件'
            }), 400
        
        file = request.files['avatar']
        if file.filename == '':
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件名为空'
            }), 400
        
        # 检查文件类型
        if not file.filename.lower().endswith(('.jpg', '.jpeg', '.png', '.gif')):
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '不支持的文件类型，仅支持 jpg, jpeg, png, gif'
            }), 400
        
        # 创建用户头像目录
        avatar_dir = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user.id}", "avatars")
        os.makedirs(avatar_dir, exist_ok=True)
        
        # 生成唯一文件名
        file_ext = os.path.splitext(file.filename)[1]
        unique_filename = f"avatar_{user.id}_{uuid.uuid4().hex}{file_ext}"
        file_path = os.path.join(avatar_dir, unique_filename)
        
        # 保存文件
        file.save(file_path)
        
        # 删除旧头像（如果存在）
        if user.avatar_url:
            old_avatar_path = os.path.join(app.config['UPLOAD_FOLDER'], user.avatar_url)
            if os.path.exists(old_avatar_path):
                try:
                    os.remove(old_avatar_path)
                except Exception as e:
                    print(f"[警告] 删除旧头像失败: {str(e)}")
        
        # 更新用户头像URL（相对路径）
        relative_path = f"user_{user.id}/avatars/{unique_filename}"
        user.avatar_url = relative_path
        db.session.commit()
        
        return jsonify({
            'success': True,
            'message': '头像上传成功',
            'data': {
                'avatar_url': relative_path
            },
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'上传头像失败: {str(e)}'
        }), 500

@app.route('/api/profile/avatar/<path:filename>', methods=['GET'])
@jwt_required()
def get_avatar(filename):
    """获取用户头像"""
    try:
        current_user = get_current_user()
        user = User.query.get(current_user['id'])
        
        if not user:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '用户不存在'
            }), 404
        
        # 确保文件名属于当前用户
        if not filename.startswith(f"user_{user.id}/"):
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '无权访问此头像'
            }), 403
        
        avatar_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
        
        if not os.path.exists(avatar_path):
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '头像文件不存在'
            }), 404
        
        return send_from_directory(
            os.path.dirname(avatar_path),
            os.path.basename(avatar_path)
        )
        
    except Exception as e:
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'获取头像失败: {str(e)}'
        }), 500

@app.route('/api/files/sync', methods=['POST'])
@jwt_required()
def sync_files():
    """同步用户文件夹中的文件到数据库"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        
        if not os.path.exists(user_folder_path):
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '用户文件夹不存在'
            }), 404
        
        synced_count = 0
        files_in_folder = []
        found_file_hashes = set()  # 记录找到的文件哈希，用于后续删除不存在的文件
        
        def get_or_create_folder(folder_name, parent_folder_id=None):
            """获取或创建文件夹"""
            # 跳过 avatars 文件夹（系统文件夹，不显示给用户）
            if folder_name.lower() == 'avatars':
                return None
            
            folder = Folder.query.filter_by(
                folder_name=folder_name,
                owner_id=user_id,
                parent_folder_id=parent_folder_id
            ).first()
            
            if not folder:
                folder = Folder(
                    folder_name=folder_name,
                    owner_id=user_id,
                    parent_folder_id=parent_folder_id
                )
                db.session.add(folder)
                db.session.flush()  # 获取ID
                print(f"[同步] 创建文件夹: {folder_name} (ID: {folder.id})")
            
            return folder
        
        def scan_directory(directory_path, relative_path="", current_folder_id=None):
            """递归扫描目录中的所有文件"""
            nonlocal synced_count, files_in_folder
            try:
                for item in os.listdir(directory_path):
                    item_path = os.path.join(directory_path, item)
                    item_relative_path = os.path.join(relative_path, item) if relative_path else item
                    
                    if os.path.isfile(item_path):
                        # 这是一个文件
                        files_in_folder.append(item_relative_path)
                        print(f"[同步] 发现文件: {item_relative_path}, folder_id: {current_folder_id}, filename: {item}")
                        
                        # 先计算文件哈希
                        file_size = os.path.getsize(item_path)
                        file_hash = calculate_file_hash(item_path)
                        
                        if not file_hash:
                            print(f"[错误] 无法计算文件哈希: {item_relative_path}")
                            continue
                        
                        # 记录找到的文件哈希
                        found_file_hashes.add(file_hash)
                        
                        # 首先通过filename（UUID）精确匹配文件（这是最准确的方式）
                        existing_file = File.query.filter_by(
                            filename=item,
                            owner_id=user_id
                        ).first()
                        
                        if existing_file:
                            # 文件已存在，检查folder_id是否需要更新
                            # 注意：只有在文件确实移动了位置时才更新folder_id
                            # 如果文件已经在正确的位置，不要更新（避免同步时错误更新）
                            if existing_file.folder_id != current_folder_id:
                                # 验证文件确实在当前位置（通过检查物理路径）
                                # 如果文件在数据库中的folder_id和实际位置不匹配，才更新
                                old_folder_id = existing_file.folder_id
                                # 检查文件是否真的在当前位置
                                expected_path = item_path
                                if os.path.exists(expected_path):
                                    existing_file.folder_id = current_folder_id
                                    synced_count += 1
                                    print(f"[同步] 更新文件folder_id: {existing_file.original_filename}, 从 {old_folder_id} 更新到 {current_folder_id}")
                                else:
                                    print(f"[同步] 警告：文件路径不匹配，不更新folder_id: {existing_file.original_filename}")
                            # 如果file_hash不同，更新file_hash（文件内容可能已改变）
                            if existing_file.file_hash != file_hash:
                                existing_file.file_hash = file_hash
                                existing_file.file_size = file_size
                                synced_count += 1
                                print(f"[同步] 更新文件哈希和大小: {existing_file.original_filename}")
                        else:
                            # 文件不存在，检查是否有相同哈希的文件（可能是重复文件）
                            existing_by_hash = File.query.filter_by(
                                file_hash=file_hash,
                                owner_id=user_id
                            ).first()
                            
                            if existing_by_hash:
                                # 发现重复文件（相同内容但不同UUID），不创建新记录
                                # 注意：不要更新folder_id，因为这是通过哈希匹配的，可能不是同一个文件
                                # 如果UUID不同，说明是不同文件，即使内容相同也不应该更新folder_id
                                print(f"[同步] 跳过重复文件（相同哈希但不同UUID）: {item} (已存在记录: {existing_by_hash.filename}, folder_id: {existing_by_hash.folder_id})")
                            else:
                                # 完全新文件，添加到数据库
                                try:
                                    # 尝试从文件名推断原始文件名和类型
                                    file_extension = item.rsplit('.', 1)[1].lower() if '.' in item else ''
                                    # 使用文件名（不含路径）作为原始文件名
                                    # 注意：这里item是UUID文件名，我们需要从上传时的original_filename获取
                                    # 但由于同步时无法知道原始文件名，我们使用UUID文件名作为original_filename
                                    original_filename = item
                                    
                                    new_file = File(
                                        filename=item,  # 存储的文件名（UUID格式）
                                        original_filename=original_filename,  # 显示的文件名（暂时使用UUID，后续可以通过其他方式更新）
                                        file_size=file_size,
                                        file_hash=file_hash,
                                        file_type=file_extension,
                                        owner_id=user_id,
                                        folder_id=current_folder_id  # 设置文件夹ID
                                    )
                                    
                                    db.session.add(new_file)
                                    synced_count += 1
                                    print(f"[同步] 添加新文件到数据库: {item}, folder_id: {current_folder_id}")
                                except Exception as e:
                                    print(f"[错误] 处理文件失败 {item_relative_path}: {str(e)}")
                                    import traceback
                                    print(traceback.format_exc())
                                    continue
                    elif os.path.isdir(item_path):
                        # 跳过 avatars 文件夹（系统文件夹，不显示给用户）
                        if item.lower() == 'avatars':
                            print(f"[同步] 跳过系统文件夹: avatars")
                            continue
                        
                        # 这是一个文件夹，获取或创建文件夹记录
                        folder = get_or_create_folder(item, current_folder_id)
                        if folder:  # 只有当文件夹不是 avatars 时才继续
                            print(f"[同步] 扫描子文件夹: {item_relative_path} (folder_id: {folder.id})")
                            # 递归扫描，传入当前文件夹ID
                            scan_directory(item_path, item_relative_path, folder.id)
            except Exception as e:
                print(f"[错误] 扫描目录失败 {directory_path}: {str(e)}")
        
        # 开始扫描用户文件夹
        print(f"[同步] 开始扫描用户文件夹: {user_folder_path}")
        scan_directory(user_folder_path)
        
        # 删除数据库中已不存在的文件记录（物理文件已删除但数据库记录还在）
        deleted_count = 0
        all_user_files = File.query.filter_by(owner_id=user_id).all()
        for db_file in all_user_files:
            # 检查文件是否在文件系统中存在
            user_folder_path_check = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
            
            # 如果文件在文件夹中，检查文件夹路径
            if db_file.folder_id is not None:
                folder = Folder.query.filter_by(id=db_file.folder_id, owner_id=user_id).first()
                if folder:
                    # 构建文件夹的完整路径（递归查找所有父文件夹）
                    folder_path_parts = [folder.folder_name]
                    current_parent = folder
                    while current_parent and current_parent.parent_folder_id:
                        parent_folder = Folder.query.filter_by(id=current_parent.parent_folder_id, owner_id=user_id).first()
                        if parent_folder:
                            folder_path_parts.insert(0, parent_folder.folder_name)
                            current_parent = parent_folder
                        else:
                            break
                    
                    # 构建完整路径
                    folder_path = os.path.join(user_folder_path_check, *folder_path_parts)
                    file_path = os.path.join(folder_path, db_file.filename)
                else:
                    # 文件夹不存在，检查根目录
                    file_path = os.path.join(user_folder_path_check, db_file.filename)
            else:
                # 文件在根目录
                file_path = os.path.join(user_folder_path_check, db_file.filename)
            
            if not os.path.exists(file_path):
                # 文件不存在，删除数据库记录
                print(f"[同步] 删除不存在的文件记录: {db_file.original_filename} (ID: {db_file.id}, filename: {db_file.filename}, folder_id: {db_file.folder_id})")
                db.session.delete(db_file)
                deleted_count += 1
        
        # 提交所有更改（包括更新和删除）
        if synced_count > 0 or deleted_count > 0:
            db.session.commit()
            print(f"[同步] 已提交 {synced_count} 个更改和 {deleted_count} 个删除到数据库")
        else:
            # 即使没有新文件，也可能有更新，尝试提交
            try:
                db.session.commit()
                print(f"[同步] 已提交所有更改到数据库")
            except:
                pass
        
        return jsonify({
            'success': True,
            'message': f'同步完成，新增 {synced_count} 个文件记录，删除 {deleted_count} 个不存在的文件记录',
            'data': {
                'synced_count': synced_count,
                'deleted_count': deleted_count,
                'files_in_folder': len(files_in_folder)
            },
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        import traceback
        print(f"[错误] 同步文件失败: {str(e)}")
        print(traceback.format_exc())
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'同步失败: {str(e)}'
        }), 500

@app.route('/api/folders', methods=['GET'])
@jwt_required()
def get_folders():
    """获取用户的文件夹列表，支持按parent_folder_id过滤"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 获取parent_folder_id参数（可选）
        parent_folder_id_str = request.args.get('parent_folder_id')
        parent_folder_id = None
        if parent_folder_id_str is not None and parent_folder_id_str.strip():
            try:
                parent_folder_id = int(parent_folder_id_str)
            except (ValueError, TypeError):
                parent_folder_id = None
        
        print(f"请求的parent_folder_id: {parent_folder_id} (原始值: '{parent_folder_id_str}')")
        
        # 获取用户的文件夹（如果parent_folder_id为None，返回根目录文件夹；否则返回指定父文件夹的子文件夹）
        query = Folder.query.filter_by(owner_id=user_id)
        if parent_folder_id is None:
            # 根目录：parent_folder_id为None的文件夹（使用is_来检查NULL）
            query = query.filter(Folder.parent_folder_id.is_(None))
            print(f"[查询] 查询根目录文件夹 (parent_folder_id IS NULL)")
        else:
            # 指定父文件夹：parent_folder_id匹配的文件夹
            query = query.filter_by(parent_folder_id=parent_folder_id)
            print(f"[查询] 查询父文件夹 {parent_folder_id} 的子文件夹")
        
        folders = query.order_by(Folder.created_date.desc()).all()
        print(f"[结果] 查询到 {len(folders)} 个文件夹 (用户ID: {user_id}, parent_folder_id: {parent_folder_id})")
        
        # 过滤掉 avatars 文件夹（隐藏系统文件夹）
        folders = [f for f in folders if f.folder_name.lower() != 'avatars']
        print(f"[结果] 过滤后剩余 {len(folders)} 个文件夹（已隐藏 avatars 文件夹）")
        
        # 调试：打印前几个文件夹的parent_folder_id，并验证过滤是否正确
        for i, f in enumerate(folders[:10]):
            print(f"[调试] 文件夹[{i}]: id={f.id}, name={f.folder_name}, parent_folder_id={f.parent_folder_id}")
            # 验证：如果查询根目录，但文件夹的parent_folder_id不是None，这是错误的
            if parent_folder_id is None and f.parent_folder_id is not None:
                print(f"[错误] 发现不匹配的文件夹: id={f.id}, name={f.folder_name}, parent_folder_id={f.parent_folder_id} (应该是None)")
            # 验证：如果查询特定父文件夹，但文件夹的parent_folder_id不匹配，这是错误的
            if parent_folder_id is not None and f.parent_folder_id != parent_folder_id:
                print(f"[错误] 发现不匹配的文件夹: id={f.id}, name={f.folder_name}, parent_folder_id={f.parent_folder_id} (应该是{parent_folder_id})")
        
        folder_list = [folder.to_dict() for folder in folders]
        
        return jsonify({
            'success': True,
            'message': '获取文件夹列表成功',
            'data': {
                'folders': folder_list,
                'count': len(folder_list)
            },
            'error': None
        }), 200
        
    except Exception as e:
        import traceback
        error_trace = traceback.format_exc()
        print(f"[错误] 获取文件夹列表错误: {str(e)}")
        print(f"[错误] 错误堆栈:\n{error_trace}")
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'获取文件夹列表失败: {str(e)}'
        }), 500

@app.route('/api/folders', methods=['POST'])
@jwt_required()
def create_folder():
    """创建文件夹"""
    try:
        # 调试日志
        auth_header = request.headers.get('Authorization', '')
        print(f"收到创建文件夹请求，Authorization头: {auth_header[:50] if auth_header else 'None'}...")
        
        current_user = get_current_user()
        print(f"当前用户: {current_user}")
        user_id = current_user['id']
        
        data = request.get_json()
        if not data or not data.get('folder_name'):
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件夹名称不能为空'
            }), 400
        
        folder_name = data.get('folder_name', '').strip()
        if not folder_name:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件夹名称不能为空'
            }), 400
        
        # 禁止创建名为 avatars 的文件夹（系统保留文件夹）
        if folder_name.lower() == 'avatars':
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件夹名称 "avatars" 为系统保留名称，不能使用'
            }), 400
        
        # 获取parent_folder_id（可选）
        parent_folder_id = data.get('parent_folder_id')
        
        # 检查文件夹名称是否已存在（同一用户下，同一父文件夹下）
        existing_folder = Folder.query.filter_by(
            folder_name=folder_name,
            owner_id=user_id,
            parent_folder_id=parent_folder_id
        ).first()
        
        if existing_folder:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件夹名称已存在'
            }), 409
        
        # 创建文件夹记录
        new_folder = Folder(
            folder_name=folder_name,
            owner_id=user_id,
            parent_folder_id=parent_folder_id
        )
        
        db.session.add(new_folder)
        db.session.commit()
        
        # 创建物理文件夹（考虑父文件夹路径）
        user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
        if parent_folder_id is not None:
            # 获取父文件夹信息
            parent_folder = Folder.query.filter_by(id=parent_folder_id, owner_id=user_id).first()
            if parent_folder:
                # 构建父文件夹的完整路径（递归查找所有父文件夹）
                folder_path_parts = [parent_folder.folder_name]
                current_parent = parent_folder
                while current_parent and current_parent.parent_folder_id:
                    grandparent_folder = Folder.query.filter_by(id=current_parent.parent_folder_id, owner_id=user_id).first()
                    if grandparent_folder:
                        folder_path_parts.insert(0, grandparent_folder.folder_name)
                        current_parent = grandparent_folder
                    else:
                        break
                
                # 构建完整路径（包含新文件夹）
                folder_path = os.path.join(user_folder_path, *folder_path_parts, folder_name)
                os.makedirs(folder_path, exist_ok=True)
                print(f"[创建文件夹] 已创建嵌套文件夹: {folder_path}")
            else:
                # 父文件夹不存在，创建到根目录
                folder_path = os.path.join(user_folder_path, folder_name)
                os.makedirs(folder_path, exist_ok=True)
                print(f"[创建文件夹] 父文件夹不存在，创建到根目录: {folder_path}")
        else:
            # 创建到根目录
            folder_path = os.path.join(user_folder_path, folder_name)
            os.makedirs(folder_path, exist_ok=True)
            print(f"[创建文件夹] 已创建根目录文件夹: {folder_path}")
        
        return jsonify({
            'success': True,
            'message': '文件夹创建成功',
            'data': new_folder.to_dict(),
            'error': None
        }), 201
        
    except Exception as e:
        db.session.rollback()
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'创建文件夹失败: {str(e)}'
        }), 500

@app.route('/api/folders/<int:folder_id>', methods=['DELETE'])
@jwt_required()
def delete_folder(folder_id):
    """删除文件夹（递归删除所有子文件夹和文件）"""
    try:
        current_user = get_current_user()
        user_id = current_user['id']
        
        # 查找文件夹
        folder = Folder.query.filter_by(id=folder_id, owner_id=user_id).first()
        
        if not folder:
            return jsonify({
                'success': False,
                'message': None,
                'data': None,
                'error': '文件夹不存在或无权访问'
            }), 404
        
        folder_name = folder.folder_name
        print(f"[删除] 开始删除文件夹: {folder_name} (ID: {folder_id})")
        
        # 递归删除所有子文件夹和文件
        deleted_files_count = 0
        deleted_folders_count = 0
        
        def delete_folder_recursive(folder_to_delete):
            """递归删除文件夹及其内容"""
            nonlocal deleted_files_count, deleted_folders_count
            
            # 1. 删除文件夹内的所有文件
            files_in_folder = File.query.filter_by(
                folder_id=folder_to_delete.id,
                owner_id=user_id
            ).all()
            
            for file in files_in_folder:
                # 删除物理文件
                user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
                
                # 构建文件夹的完整路径（递归查找所有父文件夹）
                folder_path_parts = [folder_to_delete.folder_name]
                current_parent = folder_to_delete
                while current_parent and current_parent.parent_folder_id:
                    parent_folder = Folder.query.filter_by(id=current_parent.parent_folder_id, owner_id=user_id).first()
                    if parent_folder:
                        folder_path_parts.insert(0, parent_folder.folder_name)
                        current_parent = parent_folder
                    else:
                        break
                
                # 构建完整路径
                folder_path = os.path.join(user_folder_path, *folder_path_parts)
                file_path = os.path.join(folder_path, file.filename)
                
                if os.path.exists(file_path):
                    try:
                        os.remove(file_path)
                        print(f"[删除] 已删除文件: {file_path}")
                    except Exception as e:
                        print(f"[警告] 删除文件失败: {file_path}, 错误: {str(e)}")
                
                # 删除数据库记录
                db.session.delete(file)
                deleted_files_count += 1
            
            # 2. 递归删除所有子文件夹
            sub_folders = Folder.query.filter_by(
                parent_folder_id=folder_to_delete.id,
                owner_id=user_id
            ).all()
            
            for sub_folder in sub_folders:
                delete_folder_recursive(sub_folder)
            
            # 3. 删除物理文件夹
            user_folder_path = os.path.join(app.config['UPLOAD_FOLDER'], f"user_{user_id}")
            
            # 构建文件夹的完整路径（递归查找所有父文件夹）
            folder_path_parts = [folder_to_delete.folder_name]
            current_parent = folder_to_delete
            while current_parent and current_parent.parent_folder_id:
                parent_folder = Folder.query.filter_by(id=current_parent.parent_folder_id, owner_id=user_id).first()
                if parent_folder:
                    folder_path_parts.insert(0, parent_folder.folder_name)
                    current_parent = parent_folder
                else:
                    break
            
            # 构建完整路径
            folder_path = os.path.join(user_folder_path, *folder_path_parts)
            
            if os.path.exists(folder_path):
                try:
                    shutil.rmtree(folder_path)
                    print(f"[删除] 已删除物理文件夹: {folder_path}")
                except Exception as e:
                    print(f"[警告] 删除物理文件夹失败: {folder_path}, 错误: {str(e)}")
            
            # 4. 删除数据库记录
            db.session.delete(folder_to_delete)
            deleted_folders_count += 1
        
        # 执行递归删除
        delete_folder_recursive(folder)
        
        # 提交所有更改
        db.session.commit()
        
        print(f"[删除] 删除完成: 文件夹 {folder_name}, 删除了 {deleted_files_count} 个文件和 {deleted_folders_count} 个文件夹")
        
        return jsonify({
            'success': True,
            'message': f'文件夹删除成功，已删除 {deleted_files_count} 个文件和 {deleted_folders_count} 个文件夹',
            'data': {
                'deleted_files_count': deleted_files_count,
                'deleted_folders_count': deleted_folders_count
            },
            'error': None
        }), 200
        
    except Exception as e:
        db.session.rollback()
        import traceback
        print(f"[错误] 删除文件夹失败: {str(e)}")
        print(traceback.format_exc())
        return jsonify({
            'success': False,
            'message': None,
            'data': None,
            'error': f'删除文件夹失败: {str(e)}'
        }), 500

# ========== 第七步：初始化数据库 ==========
def init_database():
    """初始化数据库，创建表和测试用户"""
    with app.app_context():
        # 确保数据库目录存在
        DATABASE_DIR.mkdir(exist_ok=True, parents=True)
        
        # 打印数据库路径
        db_path = DATABASE_DIR / "cloud_disk.db"
        print(f"数据库文件路径: {db_path}")
        print(f"数据库文件是否存在: {db_path.exists()}")
        
        # 创建所有表（如果表已存在，不会删除数据，只会创建新表）
        db.create_all()  # 创建所有表
        
        # 自动迁移：添加缺失的字段
        try:
            import sqlite3
            conn = sqlite3.connect(str(db_path))
            cursor = conn.cursor()
            
            # 检查 folder_id 字段是否已存在
            cursor.execute("PRAGMA table_info(files)")
            columns = [column[1] for column in cursor.fetchall()]
            
            if 'folder_id' not in columns:
                print("[迁移] 检测到 files 表缺少 folder_id 字段，正在添加...")
                cursor.execute("ALTER TABLE files ADD COLUMN folder_id INTEGER")
                conn.commit()
                print("[成功] folder_id 字段已成功添加")
            else:
                print("[检查] folder_id 字段已存在")
            
            # 检查 users 表的 avatar_url 字段是否已存在
            cursor.execute("PRAGMA table_info(users)")
            user_columns = [column[1] for column in cursor.fetchall()]
            
            if 'avatar_url' not in user_columns:
                print("[迁移] 检测到 users 表缺少 avatar_url 字段，正在添加...")
                cursor.execute("ALTER TABLE users ADD COLUMN avatar_url VARCHAR(255)")
                conn.commit()
                print("[成功] avatar_url 字段已成功添加")
            else:
                print("[检查] avatar_url 字段已存在")
            
            # 检查 folders 表的 share_token 和 share_expiry 字段
            cursor.execute("PRAGMA table_info(folders)")
            folder_columns = [column[1] for column in cursor.fetchall()]
            
            if 'share_token' not in folder_columns:
                print("[迁移] 检测到 folders 表缺少 share_token 字段，正在添加...")
                try:
                    # SQLite不支持在ALTER TABLE时直接添加UNIQUE约束，先添加列
                    cursor.execute("ALTER TABLE folders ADD COLUMN share_token VARCHAR(32)")
                    conn.commit()
                    print("[成功] share_token 字段已成功添加")
                except sqlite3.OperationalError as e:
                    print(f"[警告] 添加 share_token 失败: {e}")
                    # 如果失败，尝试使用TEXT类型
                    try:
                        cursor.execute("ALTER TABLE folders ADD COLUMN share_token TEXT")
                        conn.commit()
                        print("[成功] share_token 字段已成功添加（使用TEXT类型）")
                    except Exception as e2:
                        print(f"[错误] 无法添加 share_token 字段: {e2}")
            else:
                print("[检查] share_token 字段已存在")
            
            if 'share_expiry' not in folder_columns:
                print("[迁移] 检测到 folders 表缺少 share_expiry 字段，正在添加...")
                try:
                    cursor.execute("ALTER TABLE folders ADD COLUMN share_expiry DATETIME")
                    conn.commit()
                    print("[成功] share_expiry 字段已成功添加")
                except sqlite3.OperationalError as e:
                    print(f"[警告] 添加 share_expiry 失败: {e}")
                    # 如果失败，尝试使用TEXT类型
                    try:
                        cursor.execute("ALTER TABLE folders ADD COLUMN share_expiry TEXT")
                        conn.commit()
                        print("[成功] share_expiry 字段已成功添加（使用TEXT类型）")
                    except Exception as e2:
                        print(f"[错误] 无法添加 share_expiry 字段: {e2}")
            else:
                print("[检查] share_expiry 字段已存在")
            
            # 检查 users 表的 avatar_url 字段是否已存在
            cursor.execute("PRAGMA table_info(users)")
            user_columns = [column[1] for column in cursor.fetchall()]
            
            if 'avatar_url' not in user_columns:
                print("[迁移] 检测到 users 表缺少 avatar_url 字段，正在添加...")
                try:
                    cursor.execute("ALTER TABLE users ADD COLUMN avatar_url VARCHAR(255)")
                    conn.commit()
                    print("[成功] avatar_url 字段已成功添加")
                except sqlite3.OperationalError as e:
                    print(f"[警告] 添加 avatar_url 失败: {e}")
            else:
                print("[检查] avatar_url 字段已存在")
            
            conn.close()
        except Exception as e:
            print(f"[警告] 自动迁移检查失败: {e}")
            print("   如果遇到 'no such column' 错误，")
            print("   请运行: python migrate_database.py")
        
        print("[成功] 数据库表已创建/更新")
        
        # 检查是否存在测试用户
        if not User.query.filter_by(username='testuser').first():
            # 创建测试用户
            test_user = User(username='testuser', email='test@example.com')
            test_user.set_password('test123')
            db.session.add(test_user)
            db.session.commit()
            print("[成功] 已创建测试用户: testuser / test123")
        else:
            print("[成功] 测试用户已存在")
        
        print("[成功] 数据库初始化完成")

# ========== 第八步：启动应用 ==========
if __name__ == '__main__':
    print("="*60)
    print("个人云盘后端服务启动中...")
    print("="*60)
    
    # 确保必要的目录存在
    UPLOADS_DIR.mkdir(exist_ok=True, parents=True)
    DATABASE_DIR.mkdir(exist_ok=True, parents=True)
    
    print(f"项目根目录: {BASE_DIR}")
    print(f"上传目录: {UPLOADS_DIR}")
    print(f"数据库目录: {DATABASE_DIR}")
    
    # 初始化数据库
    init_database()
    
    print("="*60)
    print(f"服务器地址: http://localhost:5000")
    print(f"测试账号: testuser / test123")
    print("="*60)
    print("API接口列表:")
    print("  GET  /api/health                     - 健康检查")
    print("  POST /api/register                  - 用户注册")
    print("  POST /api/login                     - 用户登录")
    print("  GET  /api/files (需要令牌)         - 获取文件列表")
    print("  POST /api/upload (需要令牌)        - 上传文件")
    print("  POST /api/files/{id}/share (需要令牌) - 分享文件")
    print("  GET  /api/share/{token}             - 查看分享信息")
    print("  GET  /api/files/{id}/download (需要令牌) - 下载文件")
    print("  GET  /api/share/{token}/download    - 下载分享文件")
    print("  GET  /api/profile (需要令牌)         - 获取用户信息")
    print("="*60)
    print("按 Ctrl+C 停止服务器")
    print("="*60)
    
    # 启动服务器
    app.run(host='0.0.0.0', port=5000, debug=True)