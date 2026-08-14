import React, { useState } from 'react';

/**
 * SubscriptionFilter Component
 * Provides filter pills (e.g. [Filter: Hotstar], [Filter: Netflix], [Filter: Prime])
 * to filter watchlist items based on available streaming providers from TMDB/JustWatch.
 * 
 * @param {Object} props
 * @param {Array} props.items - List of watchlist items containing watchProviders or streamingProviders
 * @param {Function} props.onFilterChange - Callback returning filtered list: (filteredItems, selectedProvider) => void
 * @param {string} [props.className] - Optional Tailwind classes
 */
export default function SubscriptionFilter({ items = [], onFilterChange, className = '' }) {
  const [selectedProvider, setSelectedProvider] = useState('ALL');

  const popularProviders = [
    { id: 'ALL', name: 'All Subscriptions', icon: '📺' },
    { id: 'Hotstar', name: 'Hotstar', matchKey: 'hotstar' },
    { id: 'Netflix', name: 'Netflix', matchKey: 'netflix' },
    { id: 'Prime', name: 'Prime Video', matchKey: 'prime' },
    { id: 'Apple', name: 'Apple TV', matchKey: 'apple' },
    { id: 'JioCinema', name: 'JioCinema', matchKey: 'jio' },
  ];

  const handleSelect = (provider) => {
    setSelectedProvider(provider.id);

    if (!onFilterChange) return;

    if (provider.id === 'ALL') {
      onFilterChange(items, 'ALL');
      return;
    }

    const filtered = items.filter((item) => {
      // Check flatrate providers from watchProviders (JustWatch IN region)
      const flatrateList = item?.watchProviders?.flatrate || [];
      const hasInFlatrate = flatrateList.some((p) =>
        p?.provider_name?.toLowerCase().includes(provider.matchKey)
      );

      // Also check general streamingProviders array
      const generalList = item?.streamingProviders || [];
      const hasGeneral = generalList.some((p) =>
        p?.providerName?.toLowerCase().includes(provider.matchKey)
      );

      return hasInFlatrate || hasGeneral;
    });

    onFilterChange(filtered, provider.id);
  };

  return (
    <div className={`p-3 rounded-xl bg-gray-900 border border-gray-800 text-white ${className}`}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-bold text-gray-400 uppercase tracking-wider flex items-center gap-1.5">
          <span>🔍</span> Filter Watchlist by Subscriptions:
        </span>
        {selectedProvider !== 'ALL' && (
          <button
            onClick={() => handleSelect(popularProviders[0])}
            className="text-[11px] text-amber-400 hover:underline font-medium"
          >
            Clear Filter
          </button>
        )}
      </div>

      {/* Scrollable Provider Pills */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none">
        {popularProviders.map((prov) => {
          const isSelected = selectedProvider === prov.id;
          return (
            <button
              key={prov.id}
              onClick={() => handleSelect(prov)}
              className={`px-3 py-1.5 rounded-full text-xs font-bold transition-all whitespace-nowrap focus:outline-none ${
                isSelected
                  ? 'bg-amber-500 text-black shadow-md shadow-amber-500/20 ring-2 ring-amber-400/50'
                  : 'bg-gray-800 hover:bg-gray-700 text-gray-300 border border-gray-700/60'
              }`}
            >
              {prov.icon && <span className="mr-1">{prov.icon}</span>}
              {prov.name}
            </button>
          );
        })}
      </div>
    </div>
  );
}
