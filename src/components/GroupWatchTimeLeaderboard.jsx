import React from 'react';

/**
 * GroupWatchTimeLeaderboard Component
 * Displays total watch time stats across the group and a fun leaderboard.
 * Highlights:
 * - "Total Group Hours Watched: 420 hrs"
 * - "Most Active Binger This Month: [Avatar] Subash (112 hrs)"
 * - Top bingers leaderboard list
 * 
 * @param {Object} props
 * @param {Array} props.bingers - List of binger objects: { userId, userName, avatarUrl, hoursWatched, titlesCount, badge }
 * @param {number} [props.totalGroupHours] - Custom total group hours
 * @param {string} [props.className] - Optional Tailwind classes
 */
export default function GroupWatchTimeLeaderboard({ bingers = [], totalGroupHours = 420, className = '' }) {
  // Default sample leaderboard if none provided
  const defaultLeaderboard = [
    {
      userId: '1',
      userName: 'Subash',
      avatarUrl: 'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=100',
      hoursWatched: 112,
      titlesCount: 24,
      badge: '🥇 Binge King'
    },
    {
      userId: '2',
      userName: 'Priya',
      avatarUrl: 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=100',
      hoursWatched: 98,
      titlesCount: 19,
      badge: '🥈 Cinephile'
    },
    {
      userId: '3',
      userName: 'Rahul',
      avatarUrl: 'https://images.unsplash.com/photo-1570295999919-56ceb5ecca61?w=100',
      hoursWatched: 84,
      titlesCount: 15,
      badge: '🥉 Marathoner'
    },
    {
      userId: '4',
      userName: 'Ananya',
      avatarUrl: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100',
      hoursWatched: 65,
      titlesCount: 12,
      badge: '🍿 Night Owl'
    }
  ];

  const leaderboardData = bingers.length > 0 ? bingers : defaultLeaderboard;
  const topBinger = leaderboardData[0];
  const calculatedTotal = totalGroupHours || leaderboardData.reduce((acc, b) => acc + b.hoursWatched, 0);

  return (
    <div className={`p-4 rounded-xl bg-gray-900 border border-gray-800 text-white space-y-4 ${className}`}>
      {/* Title */}
      <h2 className="text-sm font-bold text-amber-400 flex items-center gap-2">
        <span>📊</span> Group Watch-Time Stats & Leaderboard
      </h2>

      {/* Hero Stats Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {/* Total Group Hours Card */}
        <div className="p-3.5 rounded-xl bg-gradient-to-br from-amber-500/20 to-purple-600/20 border border-amber-500/30">
          <p className="text-[11px] font-semibold text-gray-300 uppercase tracking-wide">
            Total Group Hours Watched
          </p>
          <p className="text-2xl font-black text-amber-400 mt-1">
            {calculatedTotal} <span className="text-sm font-bold text-gray-300">hrs</span>
          </p>
          <p className="text-[10px] text-gray-400 mt-0.5">Calculated from OMDb / TMDB runtime</p>
        </div>

        {/* Top Binger Card */}
        {topBinger && (
          <div className="p-3.5 rounded-xl bg-gray-800/80 border border-amber-500/40 flex items-center gap-3">
            <img
              src={topBinger.avatarUrl}
              alt={topBinger.userName}
              className="w-11 h-11 rounded-full object-cover ring-2 ring-amber-400"
            />
            <div className="min-w-0 flex-1">
              <p className="text-[10px] font-bold text-amber-400 uppercase tracking-wide">
                Most Active Binger This Month
              </p>
              <h3 className="text-sm font-bold text-white truncate">{topBinger.userName}</h3>
              <p className="text-xs font-semibold text-gray-300">
                {topBinger.hoursWatched} hrs • {topBinger.titlesCount} titles
              </p>
            </div>
          </div>
        )}
      </div>

      {/* Leaderboard Table */}
      <div className="space-y-2">
        <h3 className="text-xs font-bold text-gray-400 uppercase tracking-wider">Top Bingers Leaderboard</h3>
        <div className="space-y-2">
          {leaderboardData.map((binger, index) => (
            <div
              key={binger.userId || binger.userName}
              className="flex items-center justify-between p-2.5 rounded-lg bg-gray-800/40 border border-gray-800 hover:bg-gray-800/70 transition-colors"
            >
              <div className="flex items-center gap-3">
                <span className="w-5 text-center text-xs font-black text-gray-400">
                  #{index + 1}
                </span>
                <img
                  src={binger.avatarUrl}
                  alt={binger.userName}
                  className="w-8 h-8 rounded-full object-cover border border-gray-700"
                />
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold text-gray-200">{binger.userName}</span>
                    <span className="text-[10px] bg-amber-500/10 text-amber-300 px-1.5 py-0.5 rounded font-medium">
                      {binger.badge}
                    </span>
                  </div>
                  <span className="text-[10px] text-gray-400">{binger.titlesCount} titles completed</span>
                </div>
              </div>
              <div className="text-right">
                <span className="text-xs font-black text-amber-400">{binger.hoursWatched} hrs</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
