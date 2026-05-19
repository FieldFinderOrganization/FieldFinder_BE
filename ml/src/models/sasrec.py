"""SASRec — Self-Attentive Sequential Recommendation (Kang & McAuley, 2018)."""
from __future__ import annotations

import math
import torch
import torch.nn as nn

from .. import config as C

PAD = 0


class PointWiseFFN(nn.Module):
    def __init__(self, dim: int, dropout: float):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(dim, dim),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(dim, dim),
            nn.Dropout(dropout),
        )

    def forward(self, x):
        return self.net(x)


class SASRecBlock(nn.Module):
    def __init__(self, dim: int, num_heads: int, dropout: float):
        super().__init__()
        self.ln1 = nn.LayerNorm(dim)
        self.attn = nn.MultiheadAttention(dim, num_heads, dropout=dropout, batch_first=True)
        self.ln2 = nn.LayerNorm(dim)
        self.ffn = PointWiseFFN(dim, dropout)

    def forward(self, x, attn_mask, key_padding_mask):
        h = self.ln1(x)
        a, _ = self.attn(h, h, h, attn_mask=attn_mask, key_padding_mask=key_padding_mask, need_weights=False)
        x = x + a
        h = self.ln2(x)
        x = x + self.ffn(h)
        return x


class SASRec(nn.Module):
    def __init__(
        self,
        num_items: int,
        max_len: int = C.SASREC_MAX_LEN,
        dim: int = C.SASREC_EMB_DIM,
        num_heads: int = C.SASREC_NUM_HEADS,
        num_blocks: int = C.SASREC_NUM_BLOCKS,
        dropout: float = C.SASREC_DROPOUT,
    ):
        super().__init__()
        self.num_items = num_items
        self.max_len = max_len
        self.dim = dim

        self.item_emb = nn.Embedding(num_items, dim, padding_idx=PAD)
        self.pos_emb = nn.Embedding(max_len, dim)
        self.emb_dropout = nn.Dropout(dropout)

        self.blocks = nn.ModuleList([SASRecBlock(dim, num_heads, dropout) for _ in range(num_blocks)])
        self.ln_final = nn.LayerNorm(dim)

        self._init_weights()

    def _init_weights(self):
        nn.init.normal_(self.item_emb.weight, mean=0, std=0.02)
        nn.init.normal_(self.pos_emb.weight, mean=0, std=0.02)
        with torch.no_grad():
            self.item_emb.weight[PAD].fill_(0)

    def encode(self, input_ids: torch.Tensor) -> torch.Tensor:
        """input_ids: (B, L) → seq embedding (B, L, D)."""
        B, L = input_ids.shape
        positions = torch.arange(L, device=input_ids.device).unsqueeze(0).expand(B, L)
        x = self.item_emb(input_ids) * math.sqrt(self.dim) + self.pos_emb(positions)
        x = self.emb_dropout(x)

        # Causal mask only — bỏ key_padding_mask để tránh row toàn PAD gây NaN softmax
        attn_mask = torch.triu(torch.ones(L, L, dtype=torch.bool, device=input_ids.device), diagonal=1)

        # Padding mask vẫn dùng để zero-out output ở PAD position
        pad_mask = (input_ids == PAD).unsqueeze(-1).float()  # (B, L, 1)

        for blk in self.blocks:
            x = blk(x, attn_mask=attn_mask, key_padding_mask=None)
            # Zero PAD position để không leak vào next block
            x = x * (1.0 - pad_mask)
        x = self.ln_final(x)
        # Final guard
        x = torch.nan_to_num(x, nan=0.0)
        return x

    def forward(self, input_ids: torch.Tensor, pos_ids: torch.Tensor, neg_ids: torch.Tensor):
        """Training forward — trả pos_logits, neg_logits per step."""
        seq_out = self.encode(input_ids)  # (B, L, D)
        pos_emb = self.item_emb(pos_ids)  # (B, L, D)
        neg_emb = self.item_emb(neg_ids)
        pos_logits = (seq_out * pos_emb).sum(dim=-1)  # (B, L)
        neg_logits = (seq_out * neg_emb).sum(dim=-1)
        return pos_logits, neg_logits

    def predict(self, input_ids: torch.Tensor, candidate_ids: torch.Tensor) -> torch.Tensor:
        """Eval — score 1 candidate per user (hoặc batch candidate).
        input_ids: (B, L); candidate_ids: (B, K). Trả (B, K) score.
        """
        seq_out = self.encode(input_ids)            # (B, L, D)
        last = seq_out[:, -1, :]                    # (B, D) — vector tổng hợp
        cand_emb = self.item_emb(candidate_ids)     # (B, K, D)
        scores = torch.einsum("bd,bkd->bk", last, cand_emb)
        return scores


def bce_loss(pos_logits: torch.Tensor, neg_logits: torch.Tensor, pos_targets: torch.Tensor) -> torch.Tensor:
    """BCE loss, mask PAD position."""
    mask = (pos_targets != PAD).float()
    eps = 1e-24
    loss = -(
        torch.log(torch.sigmoid(pos_logits) + eps) * mask
        + torch.log(1 - torch.sigmoid(neg_logits) + eps) * mask
    )
    return loss.sum() / mask.sum().clamp(min=1.0)
