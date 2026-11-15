package com.lamma.cards;

/** A card the user saved under a nickname (e.g. "Whish Card"). */
public record SavedCard(long cardId, String nickname, CardMetadata details) {
}
