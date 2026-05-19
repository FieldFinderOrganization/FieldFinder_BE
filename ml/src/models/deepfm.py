"""DeepFM — Factorization Machine + Deep Neural Network (Guo et al., 2017)."""
from __future__ import annotations

from typing import List

import torch
import torch.nn as nn

from .. import config as C


class FeaturesEmbedding(nn.Module):
    """Shared embedding cho FM bậc 2 + DNN."""

    def __init__(self, field_dims: List[int], emb_dim: int):
        super().__init__()
        self.embs = nn.ModuleList([nn.Embedding(d, emb_dim) for d in field_dims])
        for emb in self.embs:
            nn.init.xavier_uniform_(emb.weight)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: (B, F) → (B, F, E)."""
        return torch.stack([emb(x[:, i]) for i, emb in enumerate(self.embs)], dim=1)


class FeaturesLinear(nn.Module):
    """FM bậc 1 — embedding 1-dim + bias."""

    def __init__(self, field_dims: List[int]):
        super().__init__()
        self.embs = nn.ModuleList([nn.Embedding(d, 1) for d in field_dims])
        self.bias = nn.Parameter(torch.zeros(1))
        for emb in self.embs:
            nn.init.xavier_uniform_(emb.weight)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: (B, F) → (B, 1)."""
        out = torch.cat([emb(x[:, i]) for i, emb in enumerate(self.embs)], dim=1)  # (B, F)
        return out.sum(dim=1, keepdim=True) + self.bias


class FMInteraction(nn.Module):
    """FM bậc 2 — closed form: 0.5 * ((sum_e)^2 - sum(e^2))."""

    def forward(self, emb: torch.Tensor) -> torch.Tensor:
        """emb: (B, F, E) → (B, 1)."""
        sum_sq = emb.sum(dim=1) ** 2          # (B, E)
        sq_sum = (emb ** 2).sum(dim=1)        # (B, E)
        return 0.5 * (sum_sq - sq_sum).sum(dim=1, keepdim=True)


class MLP(nn.Module):
    def __init__(self, in_dim: int, hidden: List[int], dropout: float):
        super().__init__()
        layers = []
        prev = in_dim
        for h in hidden:
            layers += [nn.Linear(prev, h), nn.BatchNorm1d(h), nn.ReLU(), nn.Dropout(dropout)]
            prev = h
        layers.append(nn.Linear(prev, 1))
        self.net = nn.Sequential(*layers)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)


class DeepFM(nn.Module):
    def __init__(
        self,
        field_dims: List[int],
        emb_dim: int = C.DEEPFM_EMB_DIM,
        mlp_dims: List[int] = None,
        dropout: float = C.DEEPFM_DROPOUT,
    ):
        super().__init__()
        mlp_dims = mlp_dims or C.DEEPFM_MLP_DIMS
        self.linear = FeaturesLinear(field_dims)
        self.emb = FeaturesEmbedding(field_dims, emb_dim)
        self.fm = FMInteraction()
        self.mlp = MLP(in_dim=len(field_dims) * emb_dim, hidden=mlp_dims, dropout=dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: (B, F) → logit (B,) — chưa sigmoid (dùng BCEWithLogitsLoss)."""
        emb = self.emb(x)                        # (B, F, E)
        linear_part = self.linear(x)             # (B, 1)
        fm_part = self.fm(emb)                   # (B, 1)
        deep_part = self.mlp(emb.flatten(1))     # (B, 1)
        return (linear_part + fm_part + deep_part).squeeze(1)
