import React, { useState } from 'react';

/**
 * QuickRatingsHotTakes Component
 * Allows users to rate 1-5 stars and drop a 1-sentence hot take review.
 * Displays friends' hot takes with profile avatars and ratings.
 * 
 * @param {Object} props
 * @param {Array} props.hotTakes - List of hot take objects: { id, userName, avatarUrl, rating, review }
 * @param {Function} props.onAddHotTake - Callback when user submits a hot take: (rating, review) => void
 * @param {string} [props.className] - Optional Tailwind classes
 */
export default function QuickRatingsHotTakes({ hotTakes = [], onAddHotTake, className = '' }) {
  const [rating, setRating] = useState(5);
  const [hoverRating, setHoverRating] = useState(0);
  const [review, setReview] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!review.trim()) return;
    if (onAddHotTake) {
      onAddHotTake(rating, review.trim());
    }
    setReview('');
  };

  return (
    <div className={`space-y-4 rounded-xl bg-gray-900 border border-gray-800 p-4 text-white ${className}`}>
      <h3 className="text-sm font-bold text-amber-400 flex items-center gap-2">
        <span>💬</span> Friends' Hot Takes & Quick Ratings
      </h3>

      {/* Input Box for Rating + 1-Sentence Review */}
      <form onSubmit={handleSubmit} className="space-y-3 bg-gray-800/60 p-3 rounded-lg border border-gray-700/60">
        <div className="flex items-center justify-between">
          <span className="text-xs font-semibold text-gray-300">Your Rating:</span>
          {/* 5-Star Selector */}
          <div className="flex items-center gap-1">
            {[1, 2, 3, 4, 5].map((star) => (
              <button
                key={star}
                type="button"
                onClick={() => setRating(star)}
                onMouseEnter={() => setHoverRating(star)}
                onMouseLeave={() => setHoverRating(0)}
                className="text-lg transition-transform hover:scale-125 focus:outline-none"
              >
                <span className={(hoverRating || rating) >= star ? 'text-amber-400' : 'text-gray-600'}>
                  ★
                </span>
              </button>
            ))}
            <span className="text-xs font-bold text-amber-400 ml-1.5">{rating}/5</span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <input
            type="text"
            value={review}
            onChange={(e) => setReview(e.target.value)}
            placeholder="Drop a quick 1-sentence hot take..."
            maxLength={140}
            className="flex-1 bg-gray-900 text-xs text-white placeholder-gray-500 px-3 py-2 rounded-lg border border-gray-700 focus:outline-none focus:border-amber-500"
          />
          <button
            type="submit"
            disabled={!review.trim()}
            className="px-3 py-2 bg-amber-500 hover:bg-amber-400 disabled:opacity-40 text-black text-xs font-bold rounded-lg transition-colors whitespace-nowrap"
          >
            Post Take
          </button>
        </div>
      </form>

      {/* Friends' Hot Takes List */}
      <div className="space-y-2.5 max-h-60 overflow-y-auto pr-1">
        {hotTakes.length === 0 ? (
          <p className="text-xs text-gray-500 italic text-center py-2">
            No hot takes yet. Be the first to review!
          </p>
        ) : (
          hotTakes.map((take) => (
            <div
              key={take.id || `${take.userName}-${take.timestamp}`}
              className="flex items-start gap-3 p-2.5 rounded-lg bg-gray-800/40 border border-gray-800/80"
            >
              <img
                src={take.avatarUrl || `https://i.pravatar.cc/100?u=${take.userName}`}
                alt={take.userName}
                className="w-8 h-8 rounded-full object-cover border border-amber-500/40"
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs font-bold text-gray-200">{take.userName}</span>
                  <span className="text-[11px] font-bold text-amber-400 bg-amber-500/10 px-1.5 py-0.5 rounded">
                    ★ {take.rating}/5
                  </span>
                </div>
                <p className="text-xs text-gray-300 mt-1 italic leading-snug">
                  "{take.review}"
                </p>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
