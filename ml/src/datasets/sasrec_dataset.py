"""Sequence dataset cho SASRec — leave-last-out per user."""
from __future__ import annotations

import logging
import pickle
import random
from pathlib import Path
from typing import Tuple

import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset

from .. import config as C

log = logging.getLogger(__name__)

PAD = 0  # encoder fit "<PAD>" đầu tiên → idx 0


def load_encoders():
    with open(C.PROCESSED_DIR / "encoders" / "encoders.pkl", "rb") as f:
        return pickle.load(f)


def build_user_sequences(min_len: int = C.SASREC_MIN_USER_EVENTS) -> dict[int, list[int]]:
    """Group log positive event by user, trả dict user_idx → [item_idx,...] sort theo time."""
    train = pd.read_parquet(C.PROCESSED_DIR / "logs_train.parquet")
    val = pd.read_parquet(C.PROCESSED_DIR / "logs_val.parquet")
    test = pd.read_parquet(C.PROCESSED_DIR / "logs_test.parquet")
    df = pd.concat([train, val, test], ignore_index=True)

    df = df[df["eventType"].isin(C.POSITIVE_EVENTS)]
    df = df[df["item_idx"] > 1]  # bỏ PAD/UNK
    df = df.sort_values("timestamp")

    seqs: dict[int, list[int]] = {}
    for u, g in df.groupby("user_idx"):
        items = g["item_idx"].tolist()
        if len(items) >= min_len:
            seqs[int(u)] = items
    log.info("Built sequences: %d users (min_len=%d)", len(seqs), min_len)
    return seqs


def split_seq_leave_last_out(seq: list[int]) -> Tuple[list[int], int, int]:
    """train_input = seq[:-2], val_target = seq[-2], test_target = seq[-1]."""
    return seq[:-2], seq[-2], seq[-1]


class SASRecTrainDataset(Dataset):
    """Dataset training: sliding window, predict next item, sample 1 negative per pos."""

    def __init__(self, seqs: dict[int, list[int]], num_items: int, max_len: int = C.SASREC_MAX_LEN):
        self.users = list(seqs.keys())
        self.seqs = seqs  # FULL seq, sẽ tự cắt train phần
        self.num_items = num_items
        self.max_len = max_len

    def __len__(self) -> int:
        return len(self.users)

    def _sample_neg(self, exclude: set[int]) -> int:
        # Cap attempts to prevent infinite loop khi user đã thấy gần hết catalog
        for _ in range(20):
            n = random.randint(2, self.num_items - 1)  # 0=PAD, 1=UNK
            if n not in exclude:
                return n
        # Fallback: chấp nhận sampling từ exclude (catalog quá nhỏ)
        return random.randint(2, self.num_items - 1)

    def __getitem__(self, idx: int):
        user = self.users[idx]
        full = self.seqs[user]
        train_seq, _, _ = split_seq_leave_last_out(full)

        # Lấy max_len cuối cùng
        seq = train_seq[-self.max_len :]
        seen = set(full)

        # Input: seq[:-1], Target pos: seq[1:], Target neg: random
        pad_n = self.max_len - len(seq)
        input_ids = [PAD] * pad_n + seq[:-1] if len(seq) > 1 else [PAD] * (self.max_len - 1)
        pos_ids = [PAD] * pad_n + seq[1:] if len(seq) > 1 else [PAD] * (self.max_len - 1)
        # Đảm bảo length = max_len (pad 1 phần tử ở cuối nếu cần)
        input_ids = ([PAD] + input_ids)[: self.max_len]
        pos_ids = ([PAD] + pos_ids)[: self.max_len]

        neg_ids = []
        for p in pos_ids:
            if p == PAD:
                neg_ids.append(PAD)
            else:
                neg_ids.append(self._sample_neg(seen))

        return {
            "user": torch.tensor(user, dtype=torch.long),
            "input": torch.tensor(input_ids, dtype=torch.long),
            "pos": torch.tensor(pos_ids, dtype=torch.long),
            "neg": torch.tensor(neg_ids, dtype=torch.long),
        }


class SASRecEvalDataset(Dataset):
    """Eval: input = seq[:-2 hoặc :-1], target = item cuối, kèm 100 negative để rank."""

    def __init__(
        self,
        seqs: dict[int, list[int]],
        num_items: int,
        mode: str = "val",
        max_len: int = C.SASREC_MAX_LEN,
        num_neg: int = 100,
    ):
        assert mode in ("val", "test")
        self.users = list(seqs.keys())
        self.seqs = seqs
        self.num_items = num_items
        self.mode = mode
        self.max_len = max_len
        self.num_neg = num_neg

    def __len__(self) -> int:
        return len(self.users)

    def __getitem__(self, idx: int):
        user = self.users[idx]
        full = self.seqs[user]
        train_seq, val_t, test_t = split_seq_leave_last_out(full)

        if self.mode == "val":
            seq = train_seq
            target = val_t
        else:
            seq = train_seq + [val_t]
            target = test_t

        seq = seq[-self.max_len :]
        pad_n = self.max_len - len(seq)
        input_ids = [PAD] * pad_n + seq

        seen = set(full)
        # Catalog nhỏ — nếu thiếu neg, accept duplicate hoặc reduce
        max_neg_possible = max(1, self.num_items - 2 - len(seen))
        actual_num_neg = min(self.num_neg, max_neg_possible if max_neg_possible > 0 else self.num_neg)
        negs = []
        attempts = 0
        while len(negs) < actual_num_neg and attempts < actual_num_neg * 50:
            n = random.randint(2, self.num_items - 1)
            if n not in seen and n not in negs:
                negs.append(n)
            attempts += 1
        # Pad đủ self.num_neg với random nếu thiếu
        while len(negs) < self.num_neg:
            negs.append(random.randint(2, self.num_items - 1))

        candidates = [target] + negs  # idx 0 = positive

        return {
            "user": torch.tensor(user, dtype=torch.long),
            "input": torch.tensor(input_ids, dtype=torch.long),
            "candidates": torch.tensor(candidates, dtype=torch.long),
        }


def get_dataloaders():
    """Helper: trả train/val/test DataLoader."""
    from torch.utils.data import DataLoader

    encs = load_encoders()
    num_items = len(encs["item"].classes_)

    seqs = build_user_sequences()
    train_ds = SASRecTrainDataset(seqs, num_items)
    val_ds = SASRecEvalDataset(seqs, num_items, mode="val")
    test_ds = SASRecEvalDataset(seqs, num_items, mode="test")

    train_loader = DataLoader(train_ds, batch_size=C.SASREC_BATCH, shuffle=True, num_workers=0, drop_last=True)
    val_loader = DataLoader(val_ds, batch_size=C.SASREC_BATCH, shuffle=False, num_workers=0)
    test_loader = DataLoader(test_ds, batch_size=C.SASREC_BATCH, shuffle=False, num_workers=0)

    return train_loader, val_loader, test_loader, num_items
