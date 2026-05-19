"""Central config. Load from .env, expose paths + hyperparams."""
import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()

# ---------- Paths ----------
ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT_DIR / "data"
RAW_DIR = DATA_DIR / "raw"
PROCESSED_DIR = DATA_DIR / "processed"
CHECKPOINT_DIR = ROOT_DIR / "checkpoints"

for d in (RAW_DIR, PROCESSED_DIR, CHECKPOINT_DIR):
    d.mkdir(parents=True, exist_ok=True)

# ---------- MongoDB ----------
MONGO_URI = os.getenv("MONGO_URI", "mongodb://localhost:27017")
MONGO_DB = os.getenv("MONGO_DB", "fieldfinder")
MONGO_COLLECTION_LOGS = os.getenv("MONGO_COLLECTION_LOGS", "user_interaction_logs")

# ---------- MySQL ----------
MYSQL_HOST = os.getenv("MYSQL_HOST", "localhost")
MYSQL_PORT = int(os.getenv("MYSQL_PORT", "3306"))
MYSQL_USER = os.getenv("MYSQL_USER", "root")
MYSQL_PASSWORD = os.getenv("MYSQL_PASSWORD", "")
MYSQL_DB = os.getenv("MYSQL_DB", "fieldfinder")

MYSQL_SSL = os.getenv("MYSQL_SSL", "true").lower() == "true"

MYSQL_URL = (
    f"mysql+pymysql://{MYSQL_USER}:{MYSQL_PASSWORD}"
    f"@{MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DB}?charset=utf8mb4"
)

MYSQL_CONNECT_ARGS = {"ssl": {}} if MYSQL_SSL else {}

# ---------- Data split ----------
TRAIN_RATIO = 0.8
VAL_RATIO = 0.1
TEST_RATIO = 0.1
RANDOM_SEED = 42

# ---------- SASRec ----------
SASREC_MAX_LEN = 50
SASREC_EMB_DIM = 64
SASREC_NUM_HEADS = 2
SASREC_NUM_BLOCKS = 2
SASREC_DROPOUT = 0.2
SASREC_LR = 1e-3
SASREC_BATCH = 128
SASREC_EPOCHS = 200
SASREC_MIN_USER_EVENTS = 20

# ---------- DeepFM ----------
DEEPFM_EMB_DIM = 32
DEEPFM_MLP_DIMS = [128, 64, 32]
DEEPFM_DROPOUT = 0.2
DEEPFM_LR = 5e-4
DEEPFM_BATCH = 1024
DEEPFM_EPOCHS = 80
DEEPFM_NEG_RATIO = 3

# ---------- RAG ----------
RAG_EMBED_MODEL = "bkai-foundation-models/vietnamese-bi-encoder"
RAG_TOP_K = 10
RAG_RETRIEVE_K = 50
RAG_W_QUERY = 0.5
RAG_W_USER = 0.3
RAG_W_DEEPFM = 0.2

# ---------- Image Search ----------
IMAGE_EXACT_THRESHOLD = float(os.getenv("IMAGE_EXACT_THRESHOLD", "0.85"))
IMAGE_RRF_RETURN_THRESHOLD = float(os.getenv("IMAGE_RRF_RETURN_THRESHOLD", "0.005"))

# ---------- Event types (sync với BE) ----------
POSITIVE_EVENTS = {
    "VIEW_PITCH", "VIEW_PRODUCT",
    "ADD_TO_CART",
    "CREATE_BOOKING", "CREATE_ORDER",
    "CHAT_RESULT_CLICK",
}
IMPRESSION_EVENT = "IMPRESSION_LIST"
CHAT_EVENTS = {
    "CHAT_PRODUCT_QUERY", "CHAT_PITCH_QUERY",
    "CHAT_IMAGE_SEARCH", "CHAT_WEATHER_QUERY",
    "CHAT_ACTIVITY_RECOMMEND", "CHAT_FEEDBACK",
}

ITEM_TYPES = {"PITCH", "PRODUCT"}

# ---------- Device ----------
DEVICE = os.getenv("TORCH_DEVICE", "cuda")  # fallback "cpu" trong train script
