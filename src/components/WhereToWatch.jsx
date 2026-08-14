import React from 'react';

/**
 * WhereToWatch Component
 * Renders streaming availability ("Stream On:") for the India (IN) region
 * using TMDB watch provider data.
 * 
 * @param {Object} props
 * @param {Object} [props.watchData] - Extracted IN region data containing { link, flatrate, rent, buy }
 * @param {Array} [props.watchData.flatrate] - List of flatrate subscription providers
 * @param {string} [props.watchData.link] - JustWatch deep link
 * @param {boolean} [props.loading] - Whether data is currently loading
 * @param {string} [props.className] - Additional CSS class names
 */
export default function WhereToWatch({ watchData, loading = false, className = '' }) {
  if (loading) {
    return (
      <div className={`my-4 p-4 rounded-xl bg-gray-900/80 border border-gray-800 animate-pulse ${className}`}>
        <div className="h-4 w-28 bg-gray-700/60 rounded mb-3"></div>
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 bg-gray-700/60 rounded-xl"></div>
          <div className="w-12 h-12 bg-gray-700/60 rounded-xl"></div>
          <div className="w-12 h-12 bg-gray-700/60 rounded-xl"></div>
        </div>
      </div>
    );
  }

  const flatrateProviders = watchData?.flatrate || [];
  const justWatchLink = watchData?.link;
  const hasProviders = flatrateProviders.length > 0;

  return (
    <div className={`my-4 p-4 rounded-xl bg-gray-900 border border-gray-800 text-white shadow-md ${className}`}>
      {/* Title Header */}
      <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2.5">
        Stream On:
      </h3>

      {!hasProviders ? (
        /* Fallback UI when IN object is missing or has no providers */
        <p className="text-sm text-gray-400 italic">
          Not available to stream in your region.
        </p>
      ) : (
        <div className="space-y-3">
          {/* Entire logo row wrapped in an <a> tag pointing to the JustWatch link */}
          <a
            href={justWatchLink || '#'}
            target="_blank"
            rel="noopener noreferrer"
            className="group/row inline-flex flex-wrap items-center gap-3 p-2 -ml-2 rounded-xl transition-colors hover:bg-gray-800/80 focus:outline-none focus:ring-2 focus:ring-amber-500/50"
            title="Watch on JustWatch"
          >
            {flatrateProviders.map((provider) => {
              const logoUrl = provider.logo_path
                ? `https://image.tmdb.org/t/p/w92${provider.logo_path}`
                : null;

              return (
                <div
                  key={provider.provider_id || provider.provider_name}
                  className="relative flex items-center justify-center bg-gray-800 rounded-xl overflow-hidden shadow-sm transition-transform duration-200 group-hover/row:scale-105"
                >
                  {logoUrl ? (
                    <img
                      src={logoUrl}
                      alt={provider.provider_name || 'Streaming Provider'}
                      title={provider.provider_name}
                      className="w-12 h-12 object-cover rounded-xl"
                      loading="lazy"
                    />
                  ) : (
                    <div className="w-12 h-12 flex items-center justify-center bg-gray-700 text-[10px] text-gray-300 font-bold text-center p-1">
                      {provider.provider_name}
                    </div>
                  )}
                </div>
              );
            })}
          </a>

          {/* Legal Attribution */}
          <p className="text-xs text-gray-500 font-normal">
            Streaming data provided by JustWatch
          </p>
        </div>
      )}
    </div>
  );
}
