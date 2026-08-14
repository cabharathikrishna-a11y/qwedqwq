import React from 'react';

/**
 * UpcomingAiringCalendar Component
 * Renders an "Upcoming" Airing Calendar tab / section for TV shows currently running,
 * displaying relative day countdowns (e.g., "Next episode of Season 2 drops in 3 days!").
 * 
 * @param {Object} props
 * @param {Array} props.shows - List of TV show objects with title, posterUrl, nextEpisode: { seasonNumber, episodeNumber, title, airDate }
 * @param {string} [props.className] - Optional Tailwind classes
 */
export default function UpcomingAiringCalendar({ shows = [], className = '' }) {
  // Calculate relative days remaining
  const getDaysRemaining = (airDateStr) => {
    if (!airDateStr) return null;
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const airDate = new Date(airDateStr);
    airDate.setHours(0, 0, 0, 0);
    const diffTime = airDate.getTime() - today.getTime();
    return Math.ceil(diffTime / (1000 * 60 * 60 * 24));
  };

  const upcomingShows = shows.filter((s) => s.nextEpisode && s.nextEpisode.airDate);

  return (
    <div className={`p-4 rounded-xl bg-gray-900 border border-gray-800 text-white ${className}`}>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-sm font-bold text-amber-400 flex items-center gap-2">
          <span>📅</span> "Up Next" Airing Calendar
        </h2>
        <span className="text-xs bg-amber-500/20 text-amber-300 font-medium px-2 py-0.5 rounded-full">
          {upcomingShows.length} Shows Airing
        </span>
      </div>

      {upcomingShows.length === 0 ? (
        <div className="text-center py-6 text-gray-400 text-xs italic bg-gray-800/30 rounded-lg border border-gray-800">
          No upcoming episode release dates scheduled right now.
        </div>
      ) : (
        <div className="space-y-3">
          {upcomingShows.map((show) => {
            const next = show.nextEpisode;
            const daysLeft = getDaysRemaining(next.airDate);
            
            let countdownText = 'Airing soon';
            if (daysLeft === 0) countdownText = 'Drops TODAY! 🎉';
            else if (daysLeft === 1) countdownText = 'Drops TOMORROW! ⏳';
            else if (daysLeft > 1) countdownText = `Drops in ${daysLeft} days! ⏳`;
            else if (daysLeft < 0) countdownText = `Aired ${Math.abs(daysLeft)} days ago`;

            return (
              <div
                key={show.id || show.title}
                className="flex items-center gap-3 p-3 bg-gray-800/60 rounded-xl border border-gray-700/60 transition-all hover:bg-gray-800"
              >
                {show.posterUrl && (
                  <img
                    src={show.posterUrl}
                    alt={show.title}
                    className="w-12 h-16 object-cover rounded-lg border border-gray-700"
                  />
                )}
                <div className="flex-1 min-w-0">
                  <h3 className="text-sm font-bold text-gray-100 truncate">{show.title}</h3>
                  <p className="text-xs text-amber-400 font-semibold mt-0.5">
                    Next episode of Season {next.seasonNumber} (Ep {next.episodeNumber})
                  </p>
                  {next.title && (
                    <p className="text-xs text-gray-400 truncate font-normal">"{next.title}"</p>
                  )}
                </div>
                <div className="text-right whitespace-nowrap">
                  <span className={`text-xs font-bold px-2.5 py-1 rounded-full ${
                    daysLeft === 0 ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/40' :
                    daysLeft && daysLeft <= 3 ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40' :
                    'bg-indigo-500/20 text-indigo-300 border border-indigo-500/40'
                  }`}>
                    {countdownText}
                  </span>
                  <p className="text-[10px] text-gray-500 mt-1">{next.airDate}</p>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
